package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import org.jsoup.Jsoup
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

class EbayDeCrawler(
    private val client: HttpClient,
    override val platformId: PlatformId = PlatformId.EBAY_DE,
    private val domain: String = "ebay.de",
) : Crawler, FetchesEveryPage, HasSoldListings, SellsByAuction, ReadsGalleries {

    /**
     * What eBay keeps on the item page and nowhere on the card: the vehicle specs in "Info zum
     * Artikel", and where the thing is ("Standort: Hamburg, Deutschland", city and country, never
     * a postcode). One load answers both — they were two, and the same defended page was fetched
     * twice for one ad.
     *
     * eBay answers a plain client with a challenge on an item page, so this goes straight to the
     * browser tier, primed with a search page: it serves an item page to a browser that arrives
     * from its own search and a 403 to one that arrives cold.
     */
    @Volatile private var plainRefusedAt = 0L

    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        // Straight to the item page: arriving from a search page first loaded two pages for each
        // one read, and an item page opens as well without one.
        // When eBay's edge serves its error page to every browser, the real Chrome still gets the
        // item, known by its title block.
        val html = ebayPage(listing.url, ITEM_MARKERS)
            ?: fetchWithFallback(client, listing.url, "eBay", browserOnly = true, stealthMarker = "x-item-title")
        val doc = Jsoup.parse(html)
        val stock = ebayStock(doc.text(), html)
        ListingDetail(
            vehicle = EbayDetailParser.parse(html),
            description = sellerText(doc),
            location = itemLocation(html),
            sellerReviews = sellerFeedback(doc),
            available = stock?.first,
            moreAvailable = stock?.second ?: false,
            photos = ebayGallery(doc),
            shipping = ebayItemShipping(html),
            conditionNote = ebaySellerNotes(html),
            coupon = ebayCoupon(html),
        ).takeIf { it.vehicle != null || it.location != null || it.description != null || it.photos.isNotEmpty() }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        null
    }

    /**
     * [url] read in [EbayBrowser], the Chrome eBay lets through, once it shows one of [markers].
     * eBay's edge serves its error page to a request now and then, and to the first pages of an
     * eBay site the Chrome has not read before, for up to about a minute (ebay.es on 2026-10-24:
     * refused three times over 50 s, then open for good), so the page is asked for again on a
     * widening pause before it is taken as refused. The browser is free to other pages in the
     * pauses. Null where it is refused, which leaves the caller its other tiers.
     */
    private suspend fun ebayPage(url: String, markers: List<String>, signedIn: Boolean = false): String? {
        for (pause in REFUSED_PAUSES_MS + 0L) {
            val page = (if (signedIn) accountPage(url, markers) else EbayBrowser.get(url, markers)) ?: return null
            if (page.page == "content") return page.html
            log.info("{}: Chrome got {} for {}", domain, page.page, url)
            if (page.page != "errorpage" || pause == 0L) return null
            delay(pause)
        }
        return null
    }

    /**
     * [url] read in the user's own signed-in eBay browser: eBay shows its sold listings to nobody
     * signed out, and sends every other browser to its sign-in page. Null where the account is not
     * signed in or eBay asks its password again.
     */
    private suspend fun accountPage(url: String, markers: List<String>): WarmBrowser.Page? = try {
        val answer = io.github.tieo.arbay.chat.EbayChatBrowser.call(
            "page", 60_000, "url" to url, "markers" to kotlinx.serialization.json.JsonArray(markers.map(::JsonPrimitive)), "wait" to 25,
        )
        WarmBrowser.Page(
            html = answer["html"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            url = answer["url"]?.jsonPrimitive?.contentOrNull ?: url,
            page = answer["page"]?.jsonPrimitive?.contentOrNull ?: "unknown",
        )
    } catch (e: io.github.tieo.arbay.chat.ChatFailure) {
        log.info("{}: the account's browser could not read {}: {}", domain, url, e.message)
        null
    }

    /**
     * The search read in [EbayBrowser], page after page until the search has as many offers as it
     * wants or a page comes back short, then its sold page where asked. Null where the first page
     * was refused.
     */
    private suspend fun browserSearch(query: SearchQuery): List<Listing>? {
        // Sold listings show only to a signed-in browser, which is the user's own.
        val first = ebayPage(buildSearchUrl(query, 1), SEARCH_MARKERS, signedIn = query.soldOnly) ?: return null
        val seenIds = mutableSetOf<String>()
        // A search for sold offers asks eBay for its sold listings from the first page on.
        fun read(html: String) = parseSearchResults(html).map { if (query.soldOnly) it.copy(sold = true) else it }
        var page = read(first)
        val results = page.filter { seenIds.add(it.externalId) }.toMutableList()
        // Each page streams as it is read, which is also what tells the crawl's time limit that eBay
        // is answering: without it the limit ran over the whole search and its other words, and
        // cut eBay off at 180 s while it answered every few seconds.
        emitPartialResults(results)
        val cap = query.resultCap()
        var number = 1
        while (page.size >= 20 && results.size < cap && number < query.pageLimit()) {
            number++
            val html = ebayPage(buildSearchUrl(query, number), SEARCH_MARKERS, signedIn = query.soldOnly) ?: break
            page = read(html)
            val fresh = page.filter { seenIds.add(it.externalId) }
            if (fresh.isEmpty()) break
            results += fresh
            emitPartialResults(fresh)
        }
        return results
    }

    /**
     * The seller's own description. The item page holds it in an iframe from itm.ebaydesc.com, a
     * host that answers a plain request where the item page itself needs the browser.
     */
    private suspend fun sellerText(doc: org.jsoup.nodes.Document): String? {
        val src = doc.selectFirst("iframe#desc_ifr")?.attr("src")?.takeIf { it.startsWith("https://") } ?: return null
        val page = try { fetchHttp(client, src, "eBay") } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        return descriptionText(page)
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
                EbayBrowseApi.search(client, query, platformId, domain, query.resultCap())
            } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (!apiResults.isNullOrEmpty()) return apiResults
        }

        // The Chrome eBay lets through reads the search; the tiers below are left for when it cannot.
        emitter?.emit("Chrome")
        browserSearch(query)?.let { return it }

        // eBay refuses its search to the plain and the TLS-impersonating client alike, every search
        // on ebay.de, and a refusal counts against this address as much as a burst does. After
        // one, the search goes straight to the browser, and the two are tried again an hour on.
        val plainTiers = System.currentTimeMillis() - plainRefusedAt >= PLAIN_RETRY_MS

        // Step 1: Plain HTTP
        emitter?.emit("HTTP")
        val httpHtml = if (!plainTiers) null else try {
            val html = fetchHttp(client, firstUrl, "eBay")
            validateHtml(html, "eBay")
            html
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

        if (httpHtml != null) {
            return fetchActiveAndSoldHttp(httpHtml, query, allResults, seenIds)
        }

        // Step 2: CurlCffi (Chrome TLS fingerprint — often bypasses eBay bot checks)
        emitter?.emit("CurlCffi")
        val curlHtml = if (!plainTiers) null else try {
            val html = CurlCffiClient.fetch(firstUrl, primeUrl = "https://www.$domain")
            validateHtml(html, "eBay")
            html
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

        // A CurlCffi page that parses to zero listings for a keyword search is almost always a
        // soft block (eBay serves a 200 challenge/empty page), not a genuinely empty result — so
        // fall through to the real-Chrome tier, which primes cookies and clears most soft blocks.
        val curlFirstPage = curlHtml?.let { parseSearchResults(it) }
        if (plainTiers) plainRefusedAt = if (curlFirstPage.isNullOrEmpty()) System.currentTimeMillis() else 0L
        if (curlFirstPage != null && curlFirstPage.isNotEmpty()) {
            val firstPage = curlFirstPage
            allResults.addAll(firstPage.filter { seenIds.add(it.externalId) })
            val maxPages = query.pageLimit()
            // One page already carries _ipg items (120 by default), so at the usual cap there is
            // nothing on page 2 that this search is going to keep. Asking for it anyway is not
            // free: eBay refuses every paged URL with a 403, which counts against this address
            // like any other refusal.
            val cap = query.resultCap()
            if (firstPage.size >= 20 && allResults.size < cap) {
                for (page in 2..maxPages) {
                    val html = try {
                        val h = CurlCffiClient.fetch(buildSearchUrl(query, page))
                        validateHtml(h, "eBay"); h
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {
                        // eBay refuses a later page to this tier while serving the first, so the
                        // rest are read in a browser, for a search that asked for more offers than
                        // usual: a browser session costs one of two workers for every search.
                        if (query.maxResults == null) break
                        emitter?.emit("Chromium")
                        runCatching {
                            withContext(Dispatchers.IO) {
                                HeadlessBrowser.withSession(
                                    engine = BrowserEngine.CHROMIUM, primeUrl = "https://www.$domain",
                                    waitSelector = "li.s-card, li.s-item", extraWaitMs = 1000,
                                ) { fetchPage -> browserPages(query, page, fetchPage, allResults, seenIds) }
                            }
                        }.onFailure { if (it is CancellationException) throw it }
                        break
                    }
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
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { break }
                        val sold = parseSearchResults(html).map { it.copy(sold = true) }
                            .filter { seenIds.add(it.externalId) }
                        allResults.addAll(sold)
                        if (sold.size < 20) break
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            return allResults
        }

        // Step 3: Chromium — prime once, fetch 1 active + 1 sold page (semaphore held briefly)
        emitter?.emit("Chromium")
        // eBay's edge answers one browser request in several with its "Something went wrong on our
        // end" page, and the next one goes through: of eight searches in a row, the first got it on
        // both engines and the seven after it did not. One more session is that next request.
        for (attempt in 1..2) try {
            chromiumSearch(query, allResults, seenIds)
            break
        } catch (e: CrawlerBlockedException) {
            if (!e.isEdgeErrorPage) throw e
            // The same page twice in a row is no passing hiccup: on 2026-10-17 eBay served it to
            // every client from this address, its home page included, and searches kept asking.
            // As a refusal it starts the cooldown and the watches leave eBay alone for a while.
            if (attempt == 2) throw CrawlerBlockedException(
                "eBay: error page twice in a row, taken as a block", ErrorType.BLOCKED_403, url = e.url, html = e.html,
            )
            allResults.clear()
            seenIds.clear()
        }
        return allResults
    }

    /**
     * Pages [from] onwards of a search, read in a browser session, until the search has as many
     * offers as it wants, a page comes back short or holds nothing new, or eBay refuses one. The
     * browser is the tier eBay lets through when it refuses plain requests, which it does for
     * every page past the first; a search reading only page one never saw the offers past it.
     */
    private fun browserPages(
        query: SearchQuery,
        from: Int,
        fetchPage: (url: String) -> FetchResult,
        allResults: MutableList<Listing>,
        seenIds: MutableSet<String>,
    ) {
        val cap = query.resultCap()
        for (page in from..query.pageLimit()) {
            if (allResults.size >= cap) break
            val pageUrl = buildSearchUrl(query, page)
            var pageHtml: String? = null
            // eBay's edge answers one request in several with its error page and the next one
            // goes through, so a page is asked for twice before it is believed refused.
            fun read() = parseSearchResults(validateBrowserResult(fetchPage(pageUrl), "eBay").also { pageHtml = it })
            val more = try {
                try { read() } catch (e: CrawlerBlockedException) { if (e.isEdgeErrorPage) read() else throw e }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // What eBay served instead of a later page, kept to look at: a search that stops
                // paging otherwise just reads as one with fewer offers.
                ErrorSnapshotStore.capture(
                    platform = platformId.name, query = query.text, error = e,
                    errorType = (e as? CrawlerBlockedException)?.errorType ?: ErrorType.UNKNOWN,
                    url = pageUrl, fetchStage = "chromium page $page",
                    html = (e as? CrawlerBlockedException)?.html,
                )
                break
            }
            val fresh = more.filter { seenIds.add(it.externalId) }
            allResults.addAll(fresh)
            if (fresh.isEmpty()) ErrorSnapshotStore.capture(
                platform = platformId.name, query = query.text,
                error = RuntimeException("$domain: page $page held ${more.size} offers, none new"),
                errorType = ErrorType.EMPTY_RESULTS, url = pageUrl, fetchStage = "chromium page $page", html = pageHtml,
            )
            if (more.size < 20 || fresh.isEmpty()) break
        }
    }

    private suspend fun chromiumSearch(query: SearchQuery, allResults: MutableList<Listing>, seenIds: MutableSet<String>) {
        withContext(Dispatchers.IO) {
            HeadlessBrowser.withSession(
                engine = BrowserEngine.CHROMIUM,
                primeUrl = "https://www.$domain",
                waitSelector = "li.s-card, li.s-item",
                extraWaitMs = 1000,
            ) { fetchPage ->
                val searchUrl = buildSearchUrl(query, 1)
                val result = fetchPage(searchUrl)
                val html = validateBrowserResult(result, "eBay")
                val pageResults = parseSearchResults(html)
                // The last tier coming back empty ends the crawl with nothing to show and no error,
                // so the page eBay actually served is kept to look at.
                if (pageResults.isEmpty()) {
                    ErrorSnapshotStore.capture(
                        platform = platformId.name, query = query.text,
                        error = RuntimeException("$domain: the browser page parsed to no listings"),
                        errorType = ErrorType.EMPTY_RESULTS, url = searchUrl, fetchStage = "chromium",
                        html = html, finalUrl = result.finalUrl, statusCode = result.statusCode,
                    )
                }
                allResults.addAll(pageResults.filter { seenIds.add(it.externalId) })

                if (pageResults.size >= 20) browserPages(query, 2, fetchPage, allResults, seenIds)

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
        val cap = query.resultCap()
        if (firstPage.size >= 20 && allResults.size < cap) {
            for (page in 2..maxPages) {
                val html = try {
                    val h = fetchHttp(client, buildSearchUrl(query, page), "eBay")
                    validateHtml(h, "eBay"); h
                } catch (e: CancellationException) { throw e } catch (_: Exception) { break }
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
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { break }
                    val sold = parseSearchResults(html).map { it.copy(sold = true) }
                        .filter { seenIds.add(it.externalId) }
                    allResults.addAll(sold)
                    if (sold.size < 20) break
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
        return allResults
    }

    private fun buildSearchUrl(query: SearchQuery, page: Int = 1): String {
        // For a car query on ebay.de, search by MODEL only. eBay sellers title vans "VW Crafter",
        // not "Volkswagen Crafter", so the full make token ("volkswagen") matches nothing. No
        // category is sent: eBay answers a search in its vehicle categories (_sacat 9800, 9801)
        // with 403 and never narrowed results to whole vehicles anyway; parts are removed by the
        // vehicle post-filter, and the relevance filter confirms make and model.
        val carQuery = if (domain == "ebay.de") CarQueryResolver.resolve(query.positiveText) else null
        val keyword = carQuery?.modelSlug?.replace("-", " ") ?: query.text
        val params = buildList {
            add("_nkw=${keyword.encodeUrl()}")
            // eBay lists up to 240 offers a page, and refuses its later pages to this server: a
            // search wanting more than one page of the usual size asks for the largest one.
            add("_ipg=${if ((query.maxResults ?: 0) > CrawlerConfig.current.ebayItemsPerPage) 240 else CrawlerConfig.current.ebayItemsPerPage}")
            // Cheapest first, always: the result cap decides which listings come back, and the
            // cheap end is the end a price comparison is about.
            add("_sop=15")
            if (page > 1) add("_pgn=$page")
            if (query.soldOnly) add("LH_Sold=1&LH_Complete=1")
            // For ebay.com, restrict to listings that ship to Germany
            if (domain == "ebay.com") add("_salic=DE")
            // eBay's own radius search: a postcode and a distance in kilometres. Its cards publish
            // no location, so a search area cannot be applied to what comes back — it has to be
            // asked for at the source, or every eBay listing sits outside the area unmeasurably.
            query.area(platformId.country ?: "DE")?.let { area ->
                area.zip?.let { add("_stpos=$it&_sadis=${area.radiusKm}&_fspt=1") }
            }
            // In the site's own currency: ebay.com reads a band in dollars.
            val currency = if (domain == "ebay.com") "USD" else "EUR"
            fun units(m: Money) = ExchangeRates.convert(m.amount, m.currency.name, currency) / 100
            query.minPrice?.let { add("_udlo=${units(it)}") }
            query.maxPrice?.let { add("_udhi=${units(it) + 1}") }
            query.condition?.let { conditions ->
                val conditionIds = conditions.mapNotNull { ebayConditionId(it) }
                if (conditionIds.isNotEmpty()) add("LH_ItemCondition=${conditionIds.joinToString("|")}")
            }
        }
        return "https://www.$domain/sch/i.html?${params.joinToString("&")}"
    }


    // eBay says on the card whether a price is a bid and how long is left. The time left sits in
    // its own s-card__time-left element on every eBay domain, whatever the language, so that is
    // what marks an auction; the words are read as well for layouts without the class. Read by
    // words alone, every auction on eBay.it, .es and .fr would pass as a price someone is asking.
    private val BID_MARKER = Regex("""\b(\d+)\s*(gebote?|bids?|offert[ae])\b""", RegexOption.IGNORE_CASE)
    /** "3 Gebote", "19 offerte": a count and one word, the shape the bid line has in any language. */
    private val COUNT_AND_WORD = Regex("""^(\d+)\s+\p{L}+$""")
    private val TIME_LEFT = Regex(
        """(?:noch|left|restzeit)?\s*(?:(\d+)\s*(?:t|d|g|j|tage?|days?)\b)?\s*""" +
            """(?:(\d+)\s*(?:std|h|hours?|hrs?)\b)?\s*(?:(\d+)\s*(?:min|m|mins?)\b)?""",
        RegexOption.IGNORE_CASE,
    )

    /** The card's own words about bidding: how many bids, and when it ends. */
    internal fun parseAuction(item: org.jsoup.nodes.Element, now: kotlin.time.Instant): Triple<SaleType?, kotlin.time.Instant?, Int?> {
        val timeLeft = item.selectFirst(".s-card__time-left")
        val texts = item.select("span, div").filter { it.children().isEmpty() }.map { it.text().trim() }
        val bidRowTexts = timeLeft?.parents()?.firstOrNull { it.hasClass("s-card__attribute-row") }
            ?.select("span")?.filter { it.children().isEmpty() }?.map { it.text().trim() }
        val bids = texts.firstNotNullOfOrNull { BID_MARKER.find(it) }?.groupValues?.get(1)?.toIntOrNull()
            ?: bidRowTexts?.firstNotNullOfOrNull { COUNT_AND_WORD.find(it) }?.groupValues?.get(1)?.toIntOrNull()
        val timeText = timeLeft?.text()?.trim() ?: texts.firstOrNull { t ->
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
            timeLeft != null || bids != null || endsAt != null -> SaleType.AUCTION
            else -> SaleType.FIXED_PRICE
        }
        return Triple(type, endsAt, bids)
    }

    internal fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // New eBay layout uses li.s-card, old uses li.s-item
        val newCards = doc.select("li.s-card")
        val parsed = when {
            newCards.isNotEmpty() -> parseNewLayout(newCards, now)
            else -> doc.select("li.s-item").takeIf { it.isNotEmpty() }?.let { parseOldLayout(it, now) }
                ?: emptyList()
        }
        return parsed
    }

    // An eBay card names no town. A seller in another country gets a line of its own naming that
    // country ("da Regno Unito" on eBay.it), which is read as the listing's country; a seller at
    // home gets no such line on the home market's own domain, and the market's country stands. The
    // old s-item layout had `.s-item__location` and the selector for it is still read below, for
    // the pages that still come back that way. A town would cost one item-page fetch per listing.

    private fun parseNewLayout(items: org.jsoup.select.Elements, now: kotlin.time.Instant): List<Listing> {
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
            // A listing of several choices prints its cheapest and its dearest as two prices
            // with " a " / " to " / " bis " between them, each its own s-card__price span.
            val priceUpTo = item.select(".s-card__price").mapNotNull { Money.parse(it.text()) }
                .takeIf { it.size > 1 }?.last()?.takeIf { it.currency == price.currency && it.amount > price.amount }

            val conditionText = item.selectFirst("div.s-card__subtitle-row span")?.text()
                ?: item.selectFirst(".SECONDARY_INFO")?.text()
            val condition = conditionText?.let { Condition.parse(it) }

            val locationText = item.select("span.s-card__location, span.su-styled-text.secondary.large")
                .firstOrNull { it.text().let { t -> t.contains("aus ") || t.contains("from ") || Regex("\\d{5}").containsMatchIn(t) } }
                ?.text()
            // A seller abroad is named by country on its own line ("da Regno Unito", "aus China"),
            // which is what decides import VAT.
            val origin = item.select("span.su-styled-text.secondary.large")
                .firstNotNullOfOrNull { el -> CountryNames.fromOriginLine(el.text())?.let { it to el.text() } }
            val location = locationText?.let {
                Location.parse(it.removePrefix("aus ").removePrefix("from "))
            }?.let { it.copy(country = it.country ?: origin?.first) }
                ?: origin?.let { (country, line) -> Location(country = country, raw = line) }

            val imageUrl = item.selectFirst("img.s-card__image")?.attr("src")
                ?: item.selectFirst("img")?.attr("src")

            // Shipping: "Kostenloser Versand" / "Free shipping" / "+EUR X.XX Versand" / "Postage"
            val shippingText = item.selectFirst("span.s-card__shipping")?.text()
                ?: item.selectFirst("[class*=shipping]")?.text()
                ?: item.select("span, div").firstOrNull { el ->
                    val t = el.text()
                    el.children().isEmpty() && // leaf element only
                    (t.contains("Versand", true) || t.contains("shipping", true) ||
                     t.contains("Lieferung", true) || t.contains("postage", true) ||
                     t.contains("consegna", true) || t.contains("spedizione", true)) &&
                    saysWhatDeliveryCosts(t)
                }?.text()
            val shipping = parseShipping(shippingText, price.currency)

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
                priceUpTo = priceUpTo,
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

    private fun parseOldLayout(items: org.jsoup.select.Elements, now: kotlin.time.Instant): List<Listing> {
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
            val shipping = parseShipping(shippingText, price.currency)

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

    /**
     * Whether a line states a delivery charge at all, rather than merely carrying the word.
     *
     * A seller who writes "Kein Versand !!!!" in the title says the word in a line that names no
     * charge, and reading a number out of the rest of that title produced a delivery charge of
     * 116422212 on an iPhone: the 11, 64, 2221 and 2 of "iPhone 11 Schwarz 64GB A2221 MWLT2ZD".
     * A charge is written as money, or as one of the words for free.
     */
    private fun saysWhatDeliveryCosts(text: String): Boolean {
        val lower = text.lowercase()
        return "€" in text || "eur" in lower || "$" in text || "£" in text ||
            "kostenlos" in lower || "free" in lower || "gratis" in lower || "gratuit" in lower ||
            "not specified" in lower || "nicht angegeben" in lower || "non indicat" in lower
    }

    private fun parseShipping(text: String?, marketCurrency: Currency): Shipping? {
        if (text == null || !saysWhatDeliveryCosts(text)) return null
        val lower = text.lowercase()
        return when {
            lower.contains("kostenlos") || lower.contains("free") || lower.contains("gratis") ||
                lower.contains("gratuit") -> Shipping(free = true)
            lower.contains("not specified") || lower.contains("nicht angegeben") ||
                lower.contains("non indicat") -> null
            else -> {
                val cost = Money.parseAtMarket(text, marketCurrency)
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

    internal fun parseSoldDate(text: String?, now: Instant = Clock.System.now()): Instant? {
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

            // eBay writes these dates in German time, so that is the calendar they are read in.
            val berlin = TimeZone.of("Europe/Berlin")
            val today = now.toLocalDateTime(berlin).date

            val (day, month, statedYear) = if (parts[0].all { it.isDigit() }) {
                // German: day month [year]
                Triple(parts[0].toInt(), MONTH_MAP[parts[1].take(3).lowercase()] ?: return null, parts.getOrNull(2)?.toIntOrNull())
            } else {
                // English: month day [year]
                Triple(parts[1].toInt(), MONTH_MAP[parts[0].take(3).lowercase()] ?: return null, parts.getOrNull(2)?.toIntOrNull())
            }
            // A date with no year is the last time that day came round: a sale on "31. Dez" read on
            // 3 January was last year's, not one eleven months ahead.
            val date = statedYear?.let { LocalDate(it, month, day) }
                ?: LocalDate(today.year, month, day).let { if (it > today) LocalDate(today.year - 1, month, day) else it }
            date.atStartOfDayIn(berlin)
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
        /** How long a refusal to the plain clients holds before they are tried again. */
        private val log = LoggerFactory.getLogger(EbayDeCrawler::class.java)
        private const val PLAIN_RETRY_MS = 60 * 60 * 1000L
        /** On a search page with offers and on one without; on an item page. */
        private val SEARCH_MARKERS = listOf("srp-results")
        private val ITEM_MARKERS = listOf("x-item-title")
        private val REFUSED_PAUSES_MS = listOf(5_000L, 15_000L, 30_000L, 45_000L)
        /** The text of eBay's description page, one line per paragraph or break the seller made. */
        internal fun descriptionText(page: String): String? {
            val doc = Jsoup.parse(page)
            val body = doc.selectFirst("[data-testid=x-item-description-child]") ?: doc.body() ?: return null
            body.select("script, style").remove()
            body.select("br, p, div, li, tr").forEach { it.appendText("\n") }
            return body.wholeText().lines().map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString("\n").takeIf { it.isNotBlank() }
        }

        /** The seller's feedback count, "sou_venir (532)" on the seller card. */
        internal fun sellerFeedback(doc: org.jsoup.nodes.Document): Int? =
            doc.selectFirst("[data-testid=x-sellercard-atf]")?.text()
                ?.let { Regex("""\((\d[\d.]*)\)""").find(it) }
                ?.groupValues?.get(1)?.replace(".", "")?.toIntOrNull()

        /** eBay's "new listing" flag, which it writes into the title element as a span of its own and
         *  in the language of whichever eBay this is. */
        /** Text eBay puts inside the title element for screen readers, in every language it runs
         *  in. Read as a title, a real van came back called "Wird in neuem Fenster oder Tab
         *  geöffnet" and was then dropped for carrying none of the words searched for. */
        private val screenReaderText = Regex(
            // Some of these open with a subject of their own — "La page s'ouvre dans une nouvelle
            // fenêtre ou un nouvel onglet" — so the line is recognised by what it says, not from
            // its first word. Two notifications went out titled exactly that sentence.
            """^\s*(la\s+page\s+|the\s+page\s+|de\s+pagina\s+|la\s+p(á|a)gina\s+)?""" +
                """(wird\s+in\s+neuem\s+fenster\s+oder\s+tab\s+ge(ö|oe)ffnet|""" +
                """(wordt\s+)?(ge)?opens?\s+in\s+a\s+new\s+window\s+or\s+tab|""" +
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

        /**
         * The item page's own statement of where the thing is.
         *
         * It rides in the page's data as an `itemLocation` block whose label is "Standort" and
         * whose value is the place, city and country ("Hamburg, Deutschland"). There is no postcode
         * in it, which is enough to place a listing on the map and not enough to measure a street.
         */
        internal fun itemLocation(html: String): Location? {
            val m = itemLocationJson.find(html) ?: return null
            val text = m.groupValues[1].trim().takeIf { it.isNotBlank() && it.length < 80 } ?: return null
            // "Hamburg, Deutschland" — the tail is the country, the head the town.
            val parts = text.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.isEmpty()) return null
            val country = parts.takeIf { it.size > 1 }?.last()
            val place = parts.dropLast(if (country != null) 1 else 0).joinToString(", ")
            // Location.parse names a town only when a postcode leads it, and eBay writes none.
            val parsed = Location.parse(place)
            return parsed.copy(
                city = parsed.city ?: place.takeIf { it.isNotBlank() },
                country = CountryNames.iso(country) ?: country,
                raw = text,
            )
        }

        /** The value inside eBay's own `itemLocation` block, which is JSON embedded in the page. */
        private val itemLocationJson = Regex(
            """"itemLocation"\s*:\s*\{[\s\S]{0,400}?"values"\s*:\s*\[[\s\S]{0,300}?"text"\s*:\s*"([^"]+)"""",
        )
    }

}

/**
 * How many the listing still has and whether that is a floor, read from the words the page
 * puts beside the quantity: "3 verfügbar", "Mehr als 10 verfügbar", "Letzter Artikel". Read
 * from the text rather than an element, since eBay renames its classes far more often than
 * its wording. A recertified drive sold by a dealer at one price is worth a second at the same
 * price only where there is one.
 */
/**
 * The item page's photos in their order, each at the size eBay zooms to (1600 px). The carousel
 * repeats its items to scroll round, and the page shows other sellers' items with photos of their
 * own; only the carousel's count, once each.
 */
internal fun ebayGallery(doc: org.jsoup.nodes.Document): List<String> =
    doc.select(".ux-image-carousel-item img").mapNotNull { img ->
        img.attr("data-zoom-src").ifEmpty { img.attr("src") }.takeIf { it.startsWith("https://") }
    }.distinct()

internal fun ebayStock(text: String, html: String = ""): Pair<Int, Boolean>? {
    // A sold-out listing keeps its page and its words; only the page's own data says it is out.
    if ("\"outOfStock\":true" in html) return 0 to false
    Regex("""(?i)mehr\s+als\s+(\d{1,5})\s+verf(ü|ue)gbar|more\s+than\s+(\d{1,5})\s+available""").find(text)?.let { m ->
        return (m.groupValues[1].ifEmpty { m.groupValues[3] }).toInt() to true
    }
    if (Regex("""(?i)letzter\s+artikel|nur\s+noch\s+1\s+(übrig|verfügbar)|last\s+one""").containsMatchIn(text)) return 1 to false
    Regex("""(?i)\b(\d{1,5})\s+verf(ü|ue)gbar|\b(\d{1,5})\s+available""").find(text)?.let { m ->
        return (m.groupValues[1].ifEmpty { m.groupValues[3] }).toInt() to false
    }
    return null
}

/**
 * What sending it costs, from the shipping options eBay's item page carries in its data: the
 * cheapest service reaching Germany, and whether it can be collected. A card often states nothing:
 * RaidSonic's "ICY BOX IB-3740-C31 B-Ware" carried no shipping line while its page charged 5,95 €
 * for DHL. Null where the page states no service that reaches Germany and no collection.
 */
internal fun ebayItemShipping(html: String): Shipping? {
    // An item its seller sends only at home carries no service reaching Germany, and the page
    // says so: "May not ship to Germany. Read item description or contact seller for shipping
    // options." (VIPOUTLET's SN850X on ebay.com, read from Germany). Elsewhere nothing stated is
    // nothing known.
    val nothingStated = if (NOT_SENT_HERE.containsMatchIn(html)) Shipping(available = false) else null
    val options = ebayJsonAfter(html, "\"shippingOptions\":") as? JsonArray ?: return nothingStated
    val services = options.flatMap { o -> (o as? JsonObject)?.get("shippingServices") as? JsonArray ?: JsonArray(emptyList()) }
        .mapNotNull { it as? JsonObject }
    val (collected, sent) = services.partition { s ->
        s["shippingServiceCode"]?.jsonPrimitive?.contentOrNull?.contains("pickup", ignoreCase = true) == true
    }
    val costs = sent.filter { s ->
        (s["shipToLocations"] as? JsonArray).orEmpty().any { it.jsonPrimitive.contentOrNull?.uppercase() in REACHES_GERMANY }
    }.mapNotNull { s ->
        val price = ((s["shippingCost"] as? JsonObject)?.get("original") as? JsonObject) ?: return@mapNotNull null
        val amount = price["amount"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@mapNotNull null
        val currency = price["currency"]?.jsonPrimitive?.contentOrNull?.let { c -> Currency.entries.firstOrNull { it.name == c } } ?: return@mapNotNull null
        Money(kotlin.math.round(amount * 100).toLong(), currency)
    }
    if (costs.isEmpty() && collected.isEmpty()) return nothingStated
    val cheapest = costs.minByOrNull { it.amount }
    return Shipping(cost = cheapest, free = cheapest?.amount == 0L, pickup = collected.isNotEmpty(), available = cheapest != null)
}

/** eBay's line on an item page whose seller does not send to the reader's country, Germany. */
private val NOT_SENT_HERE = Regex("""(?i)(may not|does not|doesn't) ship to germany""")

/** The places a shipping service names that include a buyer in Germany. */
private val REACHES_GERMANY = setOf("DEU", "DE", "EUROPE", "EU", "WORLDWIDE")

/**
 * What the seller says of the item's condition, which eBay keeps apart from the description
 * ("Hinweise des Verkäufers": "Kundenretoure Zustand: Gebrauchsspuren können Kratzer vorhanden
 * sein - Zubehör: vollständig, Eventuell keine OVP"). The page shows it cut short; its data holds it
 * whole, in whichever language the site runs in.
 */
internal fun ebaySellerNotes(html: String): String? {
    var from = 0
    while (true) {
        val at = html.indexOf("\"sellerNotes\":", from).takeIf { it >= 0 } ?: return null
        from = at + 1
        val values = (ebayJsonAfter(html.substring(at), "\"sellerNotes\":") as? JsonObject)?.get("values") ?: continue
        val text = textSpans(values).joinToString("").trim().trim('“', '”', '"', '„').trim()
        if (text.isNotBlank()) return text
    }
}

private fun textSpans(element: JsonElement): List<String> = when (element) {
    is JsonObject -> if (element["_type"]?.jsonPrimitive?.contentOrNull == "TextSpan") listOfNotNull(element["text"]?.jsonPrimitive?.contentOrNull)
        // The "Mehr anzeigen"/"Weniger anzeigen" toggle and its help link are the page's, not the seller's.
        else element.filterKeys { it != "expandCollapseControls" && it != "action" }.values.flatMap(::textSpans)
    is JsonArray -> element.flatMap(::textSpans)
    else -> emptyList()
}

/** The first JSON array or object written right after [key] in a page's embedded data, read to
 *  its end. The same key also names the page's translated labels ("shippingOptions":"Versandoptionen"),
 *  which are passed over. */
internal fun ebayJsonAfter(html: String, key: String): JsonElement? {
    var from = 0
    while (true) {
        val start = html.indexOf(key, from).takeIf { it >= 0 }?.let { it + key.length } ?: return null
        from = start
        val open = html.getOrNull(start)?.takeIf { it == '[' || it == '{' } ?: continue
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var inString = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                open -> depth++
                close -> if (--depth == 0) {
                    runCatching { Json.parseToJsonElement(html.substring(start, i + 1)) }.getOrNull()?.let { return it }
                    break
                }
            }
            i++
        }
    }
}

/**
 * The price an item page gives with a coupon code, and the code: RaidSonic's 125 € B-Ware box read
 * "€120,00 mit Gutscheincode" beside "4% Rabatt mit Code PERFECTEBAY4, Endet am 26. Oct., Max.
 * € 50,00". Null where the page offers none.
 */
internal fun ebayCoupon(html: String): Coupon? {
    val discounted = (ebayJsonAfter(html, "\"priceDiscount\":") as? JsonObject)?.get("value") as? JsonObject ?: return null
    val amount = discounted["value"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null
    val currency = discounted["currency"]?.jsonPrimitive?.contentOrNull?.let { c -> Currency.entries.firstOrNull { it.name == c } } ?: return null
    val terms = (ebayJsonAfter(html, "\"COUPON\":") as? JsonObject)?.get("sections")?.let { sections ->
        (sections as? JsonArray)?.flatMap { section -> ((section as? JsonObject)?.get("dataItems") as? JsonArray).orEmpty() }
            ?.map { item -> textSpans(item).filterNot { it.isBlank() }.joinToString("").trim() }
            ?.map { it.removeSuffix("Weitere Details").trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString(", ")
    }?.takeIf { it.isNotBlank() }
    val code = terms?.let { Regex("""(?i)\bcode\s+([A-Z0-9]{4,})""").find(it)?.groupValues?.get(1) }
    return Coupon(price = Money(kotlin.math.round(amount * 100).toLong(), currency), code = code, terms = terms)
}

