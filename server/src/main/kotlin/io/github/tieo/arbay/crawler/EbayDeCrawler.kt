package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jsoup.Jsoup

class EbayDeCrawler(
    private val client: HttpClient,
    override val platformId: PlatformId = PlatformId.EBAY_DE,
    private val domain: String = "ebay.de",
) : Crawler, FetchesEveryPage, HasSoldListings {

    /** eBay keeps the vehicle specs on the item page, in "Info zum Artikel", and none of them on
     *  the search card. Without this every eBay listing reaches a vehicle filter with everything
     *  unknown and is kept by default. */
    override suspend fun fetchDetailVehicle(listing: Listing): VehicleInfo? = try {
        // eBay answers 403 to every plain client on an item page, so this goes straight to the
        // browser tier. It is also the one page eBay defends hardest, and a defended page costs a
        // browser attempt per engine: bounded here so a market that will not answer costs seconds
        // rather than the whole search's budget.
        kotlinx.coroutines.withTimeoutOrNull(30_000L) {
            // Primed with a search page first: eBay serves an item page to a browser that arrives
            // from its own search and a 403 to one that arrives cold.
            val html = fetchWithFallback(
                client, listing.url, "eBay",
                primeUrl = "https://$domain/sch/i.html?_nkw=vw+crafter",
                browserOnly = true,
            )
            EbayDetailParser.parse(html)
        }
    } catch (e: Exception) {
        null
    }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val emitter = coroutineContext[FetchProgressEmitter.Key]
        val allResults = mutableListOf<Listing>()
        val seenIds = mutableSetOf<String>()
        val firstUrl = buildSearchUrl(query, 1)

        // Step 0: official eBay Browse API (free, reliable) when credentials are configured.
        // Falls through to scraping if unconfigured or the call fails/returns nothing.
        if (EbayBrowseApi.isConfigured) {
            emitter?.emit("BrowseAPI")
            val apiResults = try {
                EbayBrowseApi.search(client, query, platformId, domain, CrawlerConfig.current.maxResultsPerPlatform)
            } catch (_: Exception) { null }
            if (!apiResults.isNullOrEmpty()) return apiResults
        }

        // Step 1: Plain HTTP
        emitter?.emit("HTTP")
        val httpHtml = try {
            val html = fetchHttp(client, firstUrl, "eBay")
            validateHtml(html, "eBay")
            html
        } catch (_: Exception) { null }

        if (httpHtml != null) {
            return fetchActiveAndSoldHttp(httpHtml, query, allResults, seenIds)
        }

        // Step 2: CurlCffi (Chrome TLS fingerprint — often bypasses eBay bot checks)
        emitter?.emit("CurlCffi")
        val curlHtml = try {
            val html = CurlCffiClient.fetch(firstUrl, primeUrl = "https://www.$domain")
            validateHtml(html, "eBay")
            html
        } catch (_: Exception) { null }

        // A CurlCffi page that parses to zero listings for a keyword search is almost always a
        // soft block (eBay serves a 200 challenge/empty page), not a genuinely empty result — so
        // fall through to the real-Chrome tier, which primes cookies and clears most soft blocks.
        val curlFirstPage = curlHtml?.let { parseSearchResults(it) }
        if (curlFirstPage != null && curlFirstPage.isNotEmpty()) {
            val firstPage = curlFirstPage
            allResults.addAll(firstPage.filter { seenIds.add(it.externalId) })
            val maxPages = query.pageLimit()
            // One page already carries _ipg items (120 by default), so at the usual cap there is
            // nothing on page 2 that this search is going to keep. Asking for it anyway is not
            // free: eBay refuses every paged URL with a 403, which counts against this address
            // like any other refusal.
            val cap = CrawlerConfig.current.maxResultsPerPlatform
            if (firstPage.size >= 20 && allResults.size < cap) {
                for (page in 2..maxPages) {
                    val html = try {
                        val h = CurlCffiClient.fetch(buildSearchUrl(query, page))
                        validateHtml(h, "eBay"); h
                    } catch (_: Exception) { break }
                    val results = parseSearchResults(html).filter { seenIds.add(it.externalId) }
                    allResults.addAll(results)
                    if (results.size < 20 || allResults.size >= cap) break
                }
            }
            // Only fetch sold pages if explicitly requested (saves rate limit)
            if (query.soldOnly) {
                try {
                    val soldQuery = query.copy(soldOnly = true)
                    for (soldPage in 1..maxPages) {
                        val html = try {
                            val h = CurlCffiClient.fetch(buildSearchUrl(soldQuery, soldPage))
                            validateHtml(h, "eBay"); h
                        } catch (_: Exception) { break }
                        val sold = parseSearchResults(html).map { it.copy(sold = true) }
                            .filter { seenIds.add(it.externalId) }
                        allResults.addAll(sold)
                        if (sold.size < 20) break
                    }
                } catch (_: Exception) {}
            }
            return allResults
        }

        // Step 3: Chromium — prime once, fetch 1 active + 1 sold page (semaphore held briefly)
        emitter?.emit("Chromium")
        withContext(Dispatchers.IO) {
            HeadlessBrowser.withSession(
                engine = BrowserEngine.CHROMIUM,
                primeUrl = "https://www.$domain",
                waitSelector = "li.s-card, li.s-item",
                extraWaitMs = 1000,
            ) { fetchPage ->
                val result = try { fetchPage(buildSearchUrl(query, 1)) }
                    catch (e: Exception) { throw e }
                val html = validateBrowserResult(result, "eBay")
                val pageResults = parseSearchResults(html)
                allResults.addAll(pageResults.filter { seenIds.add(it.externalId) })

                if (query.soldOnly) {
                    try {
                        val soldResult = fetchPage(buildSearchUrl(query.copy(soldOnly = true), 1))
                        val soldHtml = validateBrowserResult(soldResult, "eBay")
                        val soldResults = parseSearchResults(soldHtml).map { it.copy(sold = true) }
                        allResults.addAll(soldResults.filter { seenIds.add(it.externalId) })
                    } catch (_: Exception) {}
                }
            }
        }

        return allResults
    }

    private suspend fun fetchActiveAndSoldHttp(
        firstHtml: String,
        query: SearchQuery,
        allResults: MutableList<Listing>,
        seenIds: MutableSet<String>,
    ): List<Listing> {
        val firstPage = parseSearchResults(firstHtml)
        allResults.addAll(firstPage.filter { seenIds.add(it.externalId) })
        val maxPages = query.pageLimit()
        val cap = CrawlerConfig.current.maxResultsPerPlatform
        if (firstPage.size >= 20 && allResults.size < cap) {
            for (page in 2..maxPages) {
                val html = try {
                    val h = fetchHttp(client, buildSearchUrl(query, page), "eBay")
                    validateHtml(h, "eBay"); h
                } catch (_: Exception) { break }
                val results = parseSearchResults(html).filter { seenIds.add(it.externalId) }
                allResults.addAll(results)
                if (results.size < 20 || allResults.size >= cap) break
            }
        }
        if (query.soldOnly) {
            try {
                val soldQuery = query.copy(soldOnly = true)
                for (soldPage in 1..maxPages) {
                    val html = try {
                        val h = fetchHttp(client, buildSearchUrl(soldQuery, soldPage), "eBay")
                        validateHtml(h, "eBay"); h
                    } catch (_: Exception) { break }
                    val sold = parseSearchResults(html).map { it.copy(sold = true) }
                        .filter { seenIds.add(it.externalId) }
                    allResults.addAll(sold)
                    if (sold.size < 20) break
                }
            } catch (_: Exception) {}
        }
        return allResults
    }

    private fun buildSearchUrl(query: SearchQuery, page: Int = 1): String {
        // For a car query on ebay.de, search the whole-vehicle category by MODEL only. eBay
        // sellers title vans "VW Crafter", not "Volkswagen Crafter", so the full make token
        // ("volkswagen") matches nothing; the model ("crafter") plus the vehicle category is the
        // reliable combination. The relevance filter still confirms make/model downstream.
        val carQuery = if (domain == "ebay.de") CarQueryResolver.resolve(query.positiveText) else null
        val keyword = carQuery?.modelSlug?.replace("-", " ") ?: query.text
        val params = buildList {
            add("_nkw=${keyword.encodeUrl()}")
            if (carQuery != null) {
                CrawlerConfig.current.ebayDeCarCategory?.takeIf { it.isNotBlank() }
                    ?.let { add("_sacat=$it") }
            }
            add("_ipg=${CrawlerConfig.current.ebayItemsPerPage}")
            // Cheapest first, always: the result cap decides which listings come back, and the
            // cheap end is the end a price comparison is about.
            add("_sop=15")
            if (page > 1) add("_pgn=$page")
            if (query.soldOnly) add("LH_Sold=1&LH_Complete=1")
            // For ebay.com, restrict to listings that ship to Germany
            if (domain == "ebay.com") add("_salic=DE")
            query.minPrice?.let { add("_udlo=${it.amount / 100}") }
            query.maxPrice?.let { add("_udhi=${it.amount / 100}") }
            query.condition?.let { conditions ->
                val conditionIds = conditions.mapNotNull { ebayConditionId(it) }
                if (conditionIds.isNotEmpty()) add("LH_ItemCondition=${conditionIds.joinToString("|")}")
            }
        }
        return "https://www.$domain/sch/i.html?${params.joinToString("&")}"
    }


    // eBay says on the card whether a price is a bid and how long is left, in whatever wording and
    // class names the current layout uses. Read from the card's own text rather than a class, since
    // the class names have changed under this parser before and the words have not.
    private val BID_MARKER = Regex("""\b(\d+)\s*(gebote?|bids?)\b""", RegexOption.IGNORE_CASE)
    private val TIME_LEFT = Regex(
        """(?:noch|left|restzeit)?\s*(?:(\d+)\s*(?:t|d|tage?|days?)\b)?\s*""" +
            """(?:(\d+)\s*(?:std|h|hours?|hrs?)\b)?\s*(?:(\d+)\s*(?:min|m|mins?)\b)?""",
        RegexOption.IGNORE_CASE,
    )

    /** The card's own words about bidding: how many bids, and when it ends. */
    internal fun parseAuction(item: org.jsoup.nodes.Element, now: kotlinx.datetime.Instant): Triple<SaleType?, kotlinx.datetime.Instant?, Int?> {
        val texts = item.select("span, div").filter { it.children().isEmpty() }.map { it.text().trim() }
        val bids = texts.firstNotNullOfOrNull { BID_MARKER.find(it) }?.groupValues?.get(1)?.toIntOrNull()
        val timeText = texts.firstOrNull { t ->
            (t.contains("Noch", true) || t.contains("left", true) || t.contains("Restzeit", true)) &&
                Regex("""\d""").containsMatchIn(t)
        }
        val endsAt = timeText?.let { t ->
            val m = TIME_LEFT.find(t) ?: return@let null
            val d = m.groupValues[1].toLongOrNull() ?: 0L
            val h = m.groupValues[2].toLongOrNull() ?: 0L
            val min = m.groupValues[3].toLongOrNull() ?: 0L
            val seconds = d * 86400 + h * 3600 + min * 60
            if (seconds <= 0L) null else now.plus(kotlin.time.Duration.parse("${seconds}s"))
        }
        val type = when {
            bids != null || endsAt != null -> SaleType.AUCTION
            else -> SaleType.FIXED_PRICE
        }
        return Triple(type, endsAt, bids)
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // New eBay layout uses li.s-card, old uses li.s-item
        val newCards = doc.select("li.s-card")
        val parsed = when {
            newCards.isNotEmpty() -> parseNewLayout(newCards, now)
            else -> doc.select("li.s-item").takeIf { it.isNotEmpty() }?.let { parseOldLayout(it, now) }
                ?: emptyList()
        }
        // A whole page of listings and not one of them says where it is: the card still carries a
        // location and this parser is no longer finding it. eBay answers a plain client and
        // curl_cffi with a challenge, so the page a crawl actually got is the only place the
        // markup can be read, and it is kept here rather than thrown away.
        if (parsed.size >= 5 && parsed.none { it.location != null }) {
            noLocationsSeen(html, parsed.size)
        }
        return parsed
    }

    private fun noLocationsSeen(html: String, count: Int) {
        if (!reportedMissingLocations.compareAndSet(false, true)) return
        runCatching {
            ErrorSnapshotStore.capture(
                platform = platformId.name,
                query = "(page parse)",
                error = CrawlerBlockedException(
                    "$count listings parsed, none with a location", ErrorType.PARSE_ERROR,
                ),
                errorType = ErrorType.PARSE_ERROR,
                url = "https://www.$domain",
                html = html,
            )
        }
    }

    /** Once per run of the server: the page is a megabyte and one copy answers the question. */
    private val reportedMissingLocations = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun parseNewLayout(items: org.jsoup.select.Elements, now: kotlinx.datetime.Instant): List<Listing> {
        return items.mapNotNull { item ->
            val titleEl = item.selectFirst("div.s-card__title")
                ?: item.selectFirst("a.s-card__link div")
                ?: return@mapNotNull null
            val title = listingTitle(titleEl)
            if (title == "Shop on eBay" || title.isBlank()) return@mapNotNull null

            val linkEl = item.selectFirst("a.s-card__link") ?: return@mapNotNull null
            val link = linkEl.attr("href").substringBefore("?")
            val externalId = extractEbayItemId(link) ?: return@mapNotNull null

            val priceText = item.selectFirst("span.s-card__price")?.text()
                ?: item.selectFirst(".s-card__price")?.text()
                ?: return@mapNotNull null
            val price = Money.parse(priceText) ?: return@mapNotNull null

            val conditionText = item.selectFirst("div.s-card__subtitle-row span")?.text()
                ?: item.selectFirst(".SECONDARY_INFO")?.text()
            val condition = conditionText?.let { Condition.parse(it) }

            val locationText = item.select("span.s-card__location, span.su-styled-text.secondary.large")
                .firstOrNull { it.text().let { t -> t.contains("aus ") || t.contains("from ") || Regex("\\d{5}").containsMatchIn(t) } }
                ?.text()
            val location = locationText?.let {
                Location.parse(it.removePrefix("aus ").removePrefix("from "))
            }

            val imageUrl = item.selectFirst("img.s-card__image")?.attr("src")
                ?: item.selectFirst("img")?.attr("src")

            // Shipping: "Kostenloser Versand" / "Free shipping" / "+EUR X.XX Versand" / "Postage"
            val shippingText = item.selectFirst("span.s-card__shipping")?.text()
                ?: item.selectFirst("[class*=shipping]")?.text()
                ?: item.select("span, div").firstOrNull { el ->
                    val t = el.text()
                    el.children().isEmpty() && // leaf element only
                    (t.contains("Versand", true) || t.contains("shipping", true) ||
                     t.contains("Lieferung", true) || t.contains("postage", true))
                }?.text()
            val shipping = parseShipping(shippingText)

            // Sold date: "Verkauft 28. Mrz 2026" / "Sold Mar 25, 2026" / "Ended ...".
            // eBay styles the price with the same "positive" class as the sold date, so the span
            // must be picked by what it says, not by its class alone — taking the first positive
            // span grabbed "EUR 36,00", which marked every live listing sold and left no date.
            val soldDateText = item.select("span.su-styled-text.positive, span")
                .map { it.text() }
                .firstOrNull { SOLD_MARKER.containsMatchIn(it) }
            val soldDate = parseSoldDate(soldDateText)
            val isSold = soldDateText != null
            val (saleType, auctionEndsAt, bidCount) = parseAuction(item, now)

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = link,
                title = title,
                price = price,
                condition = condition,
                imageUrls = listOfNotNull(imageUrl),
                location = location,
                shipping = shipping,
                sold = isSold,
                soldDate = soldDate,
                saleType = saleType,
                auctionEndsAt = auctionEndsAt,
                bidCount = bidCount,
                scrapedAt = now,
            )
        }
    }

    private fun parseOldLayout(items: org.jsoup.select.Elements, now: kotlinx.datetime.Instant): List<Listing> {
        return items.mapNotNull { item ->
            val titleEl = item.selectFirst(".s-item__title") ?: return@mapNotNull null
            val title = listingTitle(titleEl)
            if (title == "Shop on eBay" || title.isBlank()) return@mapNotNull null

            val linkEl = item.selectFirst(".s-item__link") ?: return@mapNotNull null
            val link = linkEl.attr("href").substringBefore("?")
            val externalId = extractEbayItemId(link) ?: return@mapNotNull null

            val priceText = item.selectFirst(".s-item__price")?.text() ?: return@mapNotNull null
            val price = Money.parse(priceText) ?: return@mapNotNull null

            val conditionText = item.selectFirst(".SECONDARY_INFO")?.text()
            val condition = conditionText?.let { Condition.parse(it) }

            val locationText = item.selectFirst(".s-item__location")?.text()
            val location = locationText?.let {
                Location.parse(it.removePrefix("aus ").removePrefix("from "))
            }

            val imageUrl = item.selectFirst(".s-item__image-wrapper img")?.attr("src")

            // Shipping
            val shippingText = item.selectFirst(".s-item__shipping")?.text()
                ?: item.selectFirst(".s-item__freeXDays")?.text()
                ?: item.selectFirst("[class*=shipping]")?.text()
            val shipping = parseShipping(shippingText)

            // Sold date & status
            val soldEl = item.selectFirst(".s-item__caption--signal .POSITIVE")
            val sold = soldEl != null
            val soldDateText = soldEl?.text()
                ?: item.selectFirst(".s-item__endedDate")?.text()
            val soldDate = parseSoldDate(soldDateText)
            val (saleType, auctionEndsAt, bidCount) = parseAuction(item, now)

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = link,
                title = title,
                price = price,
                condition = condition,
                imageUrls = listOfNotNull(imageUrl),
                location = location,
                shipping = shipping,
                sold = sold,
                soldDate = soldDate,
                saleType = saleType,
                auctionEndsAt = auctionEndsAt,
                bidCount = bidCount,
                scrapedAt = now,
            )
        }
    }

    private fun parseShipping(text: String?): Shipping? {
        if (text == null) return null
        val lower = text.lowercase()
        return when {
            lower.contains("kostenlos") || lower.contains("free") || lower.contains("gratis") ->
                Shipping(free = true)
            lower.contains("not specified") || lower.contains("nicht angegeben") -> null
            else -> {
                val cost = Money.parse(text)
                if (cost != null) Shipping(cost = cost)
                else null
            }
        }
    }

    private val MONTH_MAP = mapOf(
        "jan" to 1, "feb" to 2, "mär" to 3, "mar" to 3, "mrz" to 3, "apr" to 4,
        "mai" to 5, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9,
        "okt" to 10, "oct" to 10, "nov" to 11, "dez" to 12, "dec" to 12,
    )

    private val DAY_OF_WEEK = setOf("mo", "di", "mi", "do", "fr", "sa", "so", "mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** Words that actually mark a listing as ended/sold. A price is not one of them. */
    private val SOLD_MARKER = Regex("""\b(verkauft|sold|beendet|ended)\b""", RegexOption.IGNORE_CASE)

    private fun parseSoldDate(text: String?): Instant? {
        if (text == null) return null
        return try {
            // Formats seen:
            //   Search results: "Verkauft 28. Mrz 2026" / "Sold Mar 25, 2026"
            //   Detail pages:   "Mi, 31. Dez, 02:15" / "Wed, Dec 31, 02:15"
            //   Also: "Ended Dec 31, 2025" / "BEENDET"
            val cleaned = text
                .removePrefix("Verkauft").removePrefix("Sold")
                .removePrefix("Ended").removePrefix("BEENDET")
                .replace(".", "").replace(",", "").trim()
            val parts = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
                // Strip day-of-week prefix (Mo, Di, Mi, Do, Fr, Sa, So, Mon, Tue, etc.)
                .dropWhile { it.take(3).lowercase() in DAY_OF_WEEK }
                // Strip time suffix (02:15)
                .filter { !it.contains(":") }

            if (parts.size < 2) return null

            val currentYear = Clock.System.now().let {
                it.toLocalDateTime(TimeZone.UTC).year
            }

            val (day, month, year) = if (parts[0].all { it.isDigit() }) {
                // German: day month [year]
                val y = parts.getOrNull(2)?.toIntOrNull() ?: currentYear
                Triple(parts[0].toInt(), MONTH_MAP[parts[1].take(3).lowercase()] ?: return null, y)
            } else {
                // English: month day [year]
                val y = parts.getOrNull(2)?.toIntOrNull() ?: currentYear
                Triple(parts[1].toInt(), MONTH_MAP[parts[0].take(3).lowercase()] ?: return null, y)
            }
            LocalDate(year, month, day).atStartOfDayIn(TimeZone.UTC)
        } catch (_: Exception) { null }
    }

    private fun extractEbayItemId(url: String): String? {
        return Regex("""/itm/[^/]*/(\d+)""").find(url)?.groupValues?.get(1)
            ?: Regex("""/itm/(\d+)""").find(url)?.groupValues?.get(1)
    }

    private fun ebayConditionId(condition: Condition): String? = when (condition) {
        Condition.NEW -> "1000"
        Condition.LIKE_NEW -> "3000"
        Condition.VERY_GOOD -> "4000"
        Condition.GOOD -> "5000"
        Condition.ACCEPTABLE -> "6000"
        Condition.REFURBISHED -> "2500"
        Condition.PARTS_ONLY -> "7000"
        Condition.USED -> null
    }

    companion object {
        /** eBay's "new listing" flag, which it writes into the title element as a span of its own and
         *  in the language of whichever eBay this is. */
        /** Text eBay puts inside the title element for screen readers, in every language it runs
         *  in. Read as a title, a real van came back called "Wird in neuem Fenster oder Tab
         *  geöffnet" and was then dropped for carrying none of the words searched for. */
        private val screenReaderText = Regex(
            """^\s*(wird\s+in\s+neuem\s+fenster\s+oder\s+tab\s+ge(ö|oe)ffnet|""" +
                """opens\s+in\s+a\s+new\s+window\s+or\s+tab|""" +
                """si\s+apre\s+in\s+una\s+nuova\s+finestra\s+o\s+scheda|""" +
                """viene\s+aperta\s+una\s+nuova\s+finestra\s+o\s+scheda|""" +
                """se\s+abre\s+en\s+una\s+nueva\s+ventana\s+o\s+pesta(ñ|n)a|""" +
                """s.ouvre\s+dans\s+une\s+nouvelle\s+fen(ê|e)tre\s+ou\s+un\s+nouvel\s+onglet|""" +
                """wordt\s+geopend\s+in\s+een\s+nieuw\s+venster\s+of\s+tabblad)\s*$""",
            RegexOption.IGNORE_CASE,
        )

        private val newListingBadge = Regex(
            """^\s*(neues\s+angebot|new\s+listing|nuovo\s+annuncio|nuova\s+inserzione|""" +
                """nuevo\s+anuncio|nueva\s+publicaci(ó|o)n|nouvelle\s+annonce|nieuwe\s+aanbieding)\s*""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * The listing's own title, without the flag eBay puts in front of it.
         *
         * Reading the first span of the title element read that flag instead: every newly listed item
         * came back titled "Neues Angebot" or "New Listing", which then read as a placeholder title
         * and was thrown away, taking the newest listings on the market with it.
         */
        internal fun listingTitle(titleEl: org.jsoup.nodes.Element): String {
            val spans = titleEl.select("span").map { it.text().trim() }.filter { it.isNotBlank() }
            val withoutBadge = spans
                .filterNot { newListingBadge.matches(it) }
                .filterNot { screenReaderText.matches(it) }
            val text = withoutBadge.maxByOrNull { it.length } ?: titleEl.text()
            val title = newListingBadge.replace(text, "").trim()
            // Nothing but the flag and the screen-reader line: this element holds no title, and
            // saying so lets the caller look elsewhere rather than pass one of them on.
            return if (screenReaderText.matches(title)) "" else title
        }
    }

}
