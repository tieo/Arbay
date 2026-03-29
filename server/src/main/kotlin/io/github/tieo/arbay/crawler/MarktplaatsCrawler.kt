package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class MarktplaatsCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.MARKTPLAATS

    override suspend fun search(query: SearchQuery): List<Listing> {
        val allResults = mutableListOf<Listing>()
        val seenIds = mutableSetOf<String>()
        val maxPages = CrawlerConfig.current.maxPages

        for (page in 1..maxPages) {
            val pageParam = if (page > 1) "p/$page/" else ""
            val url = "https://www.marktplaats.nl/q/${pageParam}${query.positiveText.encodeUrl()}/"
            val html = try {
                CurlCffiClient.fetch(url, primeUrl = if (page == 1) "https://www.marktplaats.nl" else null)
            } catch (e: CrawlerBlockedException) {
                if (page == 1) throw e
                break
            }

            val pageResults = parseSearchResults(html)
            if (pageResults.isEmpty()) break

            val newResults = pageResults.filter { seenIds.add(it.externalId) }
            allResults.addAll(newResults)

            if (newResults.size < 10) break
        }

        return allResults
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // Marktplaats uses li.hz-Listing--list-item containers
        val items = doc.select("li.hz-Listing--list-item")
            .ifEmpty { doc.select("div.hz-Listing--list-item") }

        return items.mapNotNull { item ->
            // Link: find the first <a> with an href to /v/ (actual listing page)
            val linkEl = item.selectFirst("a[href^='/v/']") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            val url = "https://www.marktplaats.nl$href"
            val externalId = Regex("""[/-]a?(\d{7,})""").find(href)?.groupValues?.get(1)
                ?: Regex("""/m(\d+)""").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null

            // Title: h3.hz-Listing-title or similar heading
            val titleEl = item.selectFirst("h3.hz-Listing-title")
                ?: item.selectFirst(".hz-Listing-title")
                ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isBlank()) return@mapNotNull null

            // Price: span or p with hz-Listing-price class
            val priceText = item.selectFirst("span.hz-Listing-price--desktop")?.text()
                ?: item.selectFirst("p.hz-Listing-price")?.text()
                ?: item.selectFirst("[class*=hz-Listing-price]")?.text()
                ?: return@mapNotNull null
            val price = Money.parse(priceText) ?: return@mapNotNull null

            val descriptionText = item.selectFirst("div.hz-Listing-description")?.text()

            val imageUrl = item.selectFirst(".hz-Listing-image-container img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.takeIf { it.startsWith("http") }

            // Shipping: Marktplaats shows "Verzending" (shipping) / "Ophalen" (pickup)
            val shippingEl = item.select("span").firstOrNull { it.text().let { t ->
                t.contains("Verzending", true) || t.contains("Ophalen", true) || t.contains("shipping", true)
            } }?.text()
            val shipping = when {
                shippingEl == null -> null
                shippingEl.contains("Ophalen", true) -> Shipping(pickup = true)
                else -> {
                    val cost = Money.parse(shippingEl)
                    if (cost != null) Shipping(cost = cost) else null
                }
            }

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                description = descriptionText,
                shipping = shipping,
                scrapedAt = now,
            )
        }
    }
}
