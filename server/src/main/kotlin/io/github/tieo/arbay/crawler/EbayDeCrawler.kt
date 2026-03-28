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
) : Crawler {

    override suspend fun search(query: SearchQuery): List<Listing> {
        val emitter = coroutineContext[FetchProgressEmitter.Key]
        val allResults = mutableListOf<Listing>()
        val seenIds = mutableSetOf<String>()
        val firstUrl = buildSearchUrl(query, 1)

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

        if (curlHtml != null) {
            val firstPage = parseSearchResults(curlHtml)
            allResults.addAll(firstPage.filter { seenIds.add(it.externalId) })
            if (firstPage.size >= 20) {
                for (page in 2..3) {
                    val html = try {
                        val h = CurlCffiClient.fetch(buildSearchUrl(query, page))
                        validateHtml(h, "eBay"); h
                    } catch (_: Exception) { break }
                    val results = parseSearchResults(html).filter { seenIds.add(it.externalId) }
                    allResults.addAll(results)
                    if (results.size < 20) break
                }
            }
            // Only fetch sold pages if explicitly requested (saves rate limit)
            if (query.soldOnly) {
                try {
                    val soldQuery = query.copy(soldOnly = true)
                    for (soldPage in 1..3) {
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
        if (firstPage.size >= 20) {
            for (page in 2..3) {
                val html = try {
                    val h = fetchHttp(client, buildSearchUrl(query, page), "eBay")
                    validateHtml(h, "eBay"); h
                } catch (_: Exception) { break }
                val results = parseSearchResults(html).filter { seenIds.add(it.externalId) }
                allResults.addAll(results)
                if (results.size < 20) break
            }
        }
        if (query.soldOnly) {
            try {
                val soldQuery = query.copy(soldOnly = true)
                for (soldPage in 1..3) {
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
        val params = buildList {
            add("_nkw=${query.text.encodeUrl()}")
            add("_ipg=120")
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

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // New eBay layout uses li.s-card, old uses li.s-item
        val newCards = doc.select("li.s-card")
        if (newCards.isNotEmpty()) return parseNewLayout(newCards, now)

        val oldCards = doc.select("li.s-item")
        if (oldCards.isNotEmpty()) return parseOldLayout(oldCards, now)

        return emptyList()
    }

    private fun parseNewLayout(items: org.jsoup.select.Elements, now: kotlinx.datetime.Instant): List<Listing> {
        return items.mapNotNull { item ->
            val titleEl = item.selectFirst("div.s-card__title span")
                ?: item.selectFirst("a.s-card__link div span")
                ?: return@mapNotNull null
            val title = titleEl.text().trim()
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

            // Sold date: "Verkauft 28. Mrz 2026" / "Sold Mar 25, 2026" / "Ended ..."
            val soldDateText = item.selectFirst("span.su-styled-text.positive")?.text()
                ?: item.select("span").firstOrNull { it.text().let { t ->
                    t.startsWith("Verkauft", true) || t.startsWith("Sold", true) ||
                    t.startsWith("Ended", true) || t.startsWith("Beendet", true)
                } }?.text()
            val soldDate = parseSoldDate(soldDateText)
            val isSold = soldDate != null || soldDateText != null

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
                scrapedAt = now,
            )
        }
    }

    private fun parseOldLayout(items: org.jsoup.select.Elements, now: kotlinx.datetime.Instant): List<Listing> {
        return items.mapNotNull { item ->
            val titleEl = item.selectFirst(".s-item__title") ?: return@mapNotNull null
            val title = titleEl.text()
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
}
