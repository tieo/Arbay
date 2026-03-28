package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class KleinanzeigenCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.KLEINANZEIGEN

    override suspend fun search(query: SearchQuery): List<Listing> {
        val allResults = mutableListOf<Listing>()
        val seenIds = mutableSetOf<String>()
        val maxPages = 3

        for (page in 1..maxPages) {
            val pageSegment = if (page > 1) "seite:$page/" else ""
            val url = "https://www.kleinanzeigen.de/s-${pageSegment}${query.positiveText.encodeUrl()}/k0"
            val html = try {
                fetchWithFallback(client, url, "Kleinanzeigen", waitSelector = "article.aditem")
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

        val items = doc.select("article.aditem")

        return items.mapNotNull { item ->
            val adId = item.attr("data-adid").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val titleEl = item.selectFirst("h2.text-module-begin a.ellipsis")
                ?: item.selectFirst("a.ellipsis")
                ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isBlank()) return@mapNotNull null

            val href = titleEl.attr("href").takeIf { it.isNotBlank() }
                ?: item.attr("data-href")
            val url = "https://www.kleinanzeigen.de$href"

            val priceText = item.selectFirst("p.aditem-main--middle--price-shipping--price")?.text()
                ?: return@mapNotNull null
            val negotiable = priceText.contains("VB", ignoreCase = true)
            val price = Money.parse(priceText) ?: return@mapNotNull null

            val locationText = item.selectFirst("div.aditem-main--top--left")?.text()?.trim()
            val location = locationText?.let { Location.parse(it) }

            val imageUrl = item.selectFirst("div.aditem-image img")?.let {
                val src = it.attr("src")
                val srcset = it.attr("srcset")
                srcset.takeIf { s -> s.isNotBlank() } ?: src.takeIf { s -> s.startsWith("http") }
            }

            val descriptionSnippet = item.selectFirst("p.aditem-main--middle--description")?.text()

            // Kleinanzeigen: shipping/pickup info from the listing
            val shippingText = item.selectFirst("p.aditem-main--middle--price-shipping--shipping")?.text()
                ?: item.selectFirst("[class*=shipping]")?.text()
            val shipping = when {
                shippingText == null -> null
                shippingText.contains("Versand", true) -> {
                    val cost = Money.parse(shippingText)
                    if (cost != null) Shipping(cost = cost) else Shipping(available = true)
                }
                else -> null
            }

            Listing(
                id = "${platformId.name}:$adId",
                platformId = platformId,
                externalId = adId,
                url = url,
                title = title,
                price = price,
                shipping = shipping,
                negotiable = negotiable,
                imageUrls = listOfNotNull(imageUrl),
                location = location,
                description = descriptionSnippet,
                scrapedAt = now,
            )
        }
    }
}
