package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class AmazonDeCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.AMAZON_DE

    override suspend fun search(query: SearchQuery): List<Listing> {
        val allResults = mutableListOf<Listing>()
        val seenIds = mutableSetOf<String>()
        val maxPages = CrawlerConfig.current.maxPages.coerceAtMost(3) // Amazon blocks after ~3

        for (page in 1..maxPages) {
            val url = buildSearchUrl(query, page)
            val html = try {
                CurlCffiClient.fetch(url, primeUrl = if (page == 1) "https://www.amazon.de" else null)
            } catch (e: Exception) {
                if (page == 1) throw e
                break
            }
            val pageResults = parseSearchResults(html)
            if (pageResults.isEmpty()) {
                if (page == 1) {
                    // No results on page 1 — check if Amazon served a bot-detection/challenge page
                    // (returned as HTTP 200, so detectCaptcha may have missed it)
                    val lower = html.lowercase()
                    val hasNoResultsSignal = lower.contains("keine ergebnisse") ||
                        lower.contains("keinen treffer") ||
                        lower.contains("no results for") ||
                        lower.contains("did not match any") ||
                        lower.contains("data-component-type") // real search page (0 results is ok)
                    if (!hasNoResultsSignal && html.length > 10_000) {
                        throw CrawlerBlockedException("Amazon: bot detection", ErrorType.BLOCKED_403)
                    }
                }
                break
            }

            val newResults = pageResults.filter { seenIds.add(it.externalId) }
            allResults.addAll(newResults)

            if (newResults.size < 10) break
        }

        return allResults
    }

    private fun buildSearchUrl(query: SearchQuery, page: Int = 1): String {
        val params = buildList {
            add("k=${query.positiveText.encodeUrl()}")
            if (page > 1) add("page=$page")
            query.minPrice?.let { add("low-price=${it.amount / 100}") }
            query.maxPrice?.let { add("high-price=${it.amount / 100}") }
        }
        return "https://www.amazon.de/s?${params.joinToString("&")}"
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        val items = doc.select("[data-component-type=s-search-result]")

        return items.mapNotNull { item ->
            val asin = item.attr("data-asin").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val titleEl = item.selectFirst("h2 a span") ?: item.selectFirst("h2 span") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isBlank()) return@mapNotNull null

            val linkEl = item.selectFirst("h2 a")
            val href = linkEl?.attr("href") ?: "/dp/$asin"
            val url = if (href.startsWith("http")) href.substringBefore("?ref=")
            else "https://www.amazon.de${href.substringBefore("?ref=")}"

            val priceWhole = item.selectFirst(".a-price-whole")?.ownText()
                ?.replace(".", "")?.replace(",", "")?.trim()
            val priceFraction = item.selectFirst(".a-price-fraction")?.text()?.trim() ?: "00"
            val price = if (!priceWhole.isNullOrBlank()) {
                val cents = "${priceWhole}${priceFraction}".toLongOrNull()
                cents?.let { Money(it, Currency.EUR) }
            } else null
            price ?: return@mapNotNull null

            val oldPriceEl = item.selectFirst(".a-text-price .a-offscreen")
            val oldPrice = oldPriceEl?.text()?.let { Money.parse(it) }

            val imageUrl = item.selectFirst(".s-image")?.attr("src")

            // Shipping: Amazon shows "Kostenlose Lieferung" or "+X,XX € Versand" or "GRATIS-Lieferung"
            // Amazon items from search are almost always Prime-eligible with free shipping.
            // If we find an explicit shipping cost, use it; otherwise assume free (Prime).
            val shippingText = item.select("span, div").firstOrNull { el ->
                val t = el.text()
                el.children().isEmpty() &&
                (t.contains("Lieferung", true) || t.contains("Versand", true) ||
                 t.contains("delivery", true) || t.contains("GRATIS", true))
            }?.text()
            val shipping = when {
                shippingText != null && (shippingText.contains("kostenlos", true) ||
                    shippingText.contains("GRATIS", true) || shippingText.contains("free", true)) -> Shipping(free = true)
                shippingText != null -> Money.parse(shippingText)?.let { Shipping(cost = it) } ?: Shipping(free = true)
                else -> Shipping(free = true) // Amazon Prime default
            }

            Listing(
                id = "${platformId.name}:$asin",
                platformId = platformId,
                externalId = asin,
                url = url,
                title = title,
                price = price,
                oldPrice = oldPrice,
                condition = Condition.NEW,
                imageUrls = listOfNotNull(imageUrl),
                shipping = shipping,
                scrapedAt = now,
            )
        }
    }
}
