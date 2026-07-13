package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class GeizhalsCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.GEIZHALS

    override suspend fun search(query: SearchQuery): List<Listing> {
        val url = "https://geizhals.de/?fs=${query.positiveText.encodeUrl()}"
        // A current Chrome TLS fingerprint (rnet, step 2 of the chain) clears the Cloudflare
        // TLS check that older curl profiles and the plain HTTP client fail; the browser tiers
        // remain as a fallback.
        val html = fetchWithFallback(
            client, url, "Geizhals",
            primeUrl = "https://geizhals.de",
            waitNetworkIdle = true,
        )
        return parseSearchResults(html)
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // Gallery view (default) or list view
        val items = doc.select("article.galleryview__item")
            .ifEmpty { doc.select(".listview__item") }
            .ifEmpty { doc.select(".productlist__product") }

        return items.mapNotNull { item ->
            val titleEl = item.selectFirst("h3.galleryview__name a")
                ?: item.selectFirst(".galleryview__name-link")
                ?: item.selectFirst(".productlist__link")
                ?: item.selectFirst(".listview__name a")
                ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isBlank()) return@mapNotNull null

            val href = titleEl.attr("href")
            val url = if (href.startsWith("http")) href else "https://geizhals.de$href"

            val externalId = Regex("""[/-][av]?(\d{5,})""").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null

            val priceText = item.selectFirst(".galleryview__price span.price")?.text()
                ?: item.selectFirst(".galleryview__price-link .price")?.text()
                ?: item.selectFirst(".productlist__price")?.text()
                ?: item.selectFirst(".listview__price")?.text()
                ?: item.selectFirst("[class*=price]")?.text()
                ?: return@mapNotNull null
            val price = Money.parse(priceText) ?: return@mapNotNull null

            val imageUrl = item.selectFirst("img.galleryview__image")?.attr("src")
                ?: item.selectFirst("img")?.let {
                    it.attr("src").ifBlank { it.attr("data-src") }
                }?.takeIf { it.startsWith("http") }

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                condition = Condition.NEW,
                imageUrls = listOfNotNull(imageUrl),
                scrapedAt = now,
            )
        }
    }
}
