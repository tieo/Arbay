package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class ImmoScout24Crawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.IMMOSCOUT24

    override suspend fun search(query: SearchQuery): List<Listing> {
        val url = "https://www.immobilienscout24.de/Suche/de/wohnung-mieten?enteredFrom=result_list&searchQuery=${query.positiveText.encodeUrl()}"
        val html = fetchWithFallback(
            client, url, "ImmobilienScout24",
            waitSelector = "[data-testid=result-list-entry], .result-list__listing, article[data-id]",
        )
        return parseSearchResults(html)
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        val items = doc.select("[data-testid=result-list-entry]")
            .ifEmpty { doc.select(".result-list__listing") }
            .ifEmpty { doc.select("article[data-id]") }
            .ifEmpty { doc.select("a[href*='/expose/']") }

        return items.mapNotNull { item ->
            val linkEl = item.selectFirst("a[href*='/expose/']")
                ?: (if (item.tagName() == "a") item else return@mapNotNull null)
            val href = linkEl.attr("href")
            val url = if (href.startsWith("http")) href else "https://www.immobilienscout24.de$href"

            val externalId = Regex("""/expose/(\d+)""").find(href)?.groupValues?.get(1)
                ?: item.attr("data-id").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val title = item.selectFirst("[data-testid=listing-title]")?.text()
                ?: item.selectFirst("h2, h3")?.text()
                ?: item.selectFirst("[class*=title]")?.text()
                ?: return@mapNotNull null

            val priceText = item.selectFirst("[data-testid=listing-price]")?.text()
                ?: item.selectFirst("[class*=price]")?.text()
                ?: return@mapNotNull null
            val price = Money.parse(priceText) ?: return@mapNotNull null

            val locationText = item.selectFirst("[data-testid=listing-location]")?.text()
                ?: item.selectFirst("[class*=location], [class*=address]")?.text()
            val location = locationText?.let { Location.parse(it) }

            val imageUrl = item.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.takeIf { it.startsWith("http") }

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                location = location,
                scrapedAt = now,
            )
        }
    }
}
