package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class MobileDeCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.MOBILE_DE

    override suspend fun search(query: SearchQuery): List<Listing> {
        val url = "https://suchen.mobile.de/fahrzeuge/search.html?dam=0&isSearchRequest=true&s=Car&sb=rel&vc=Car&q=${query.positiveText.encodeUrl()}"
        val html = fetchWithFallback(
            client, url, "mobile.de",
            waitSelector = "[data-testid=result-listing], .cBox-body--resultitem, a[href*='/fahrzeuge/details']",
        )
        return parseSearchResults(html)
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        val items = doc.select("[data-testid=result-listing]")
            .ifEmpty { doc.select(".cBox-body--resultitem") }
            .ifEmpty { doc.select("[class*=ListItem]") }
            .ifEmpty { doc.select("a[href*='/fahrzeuge/details']") }

        return items.mapNotNull { item ->
            val linkEl = item.selectFirst("a[href*='/fahrzeuge/details']")
                ?: item.selectFirst("a[href*='details.html']")
                ?: (if (item.tagName() == "a") item else return@mapNotNull null)
            val href = linkEl.attr("href")
            val url = if (href.startsWith("http")) href else "https://suchen.mobile.de$href"

            val externalId = Regex("""details[./](\d+)""").find(href)?.groupValues?.get(1)
                ?: Regex("""/(\d+)""").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null

            val title = item.selectFirst("[data-testid=result-listing-title]")?.text()
                ?: item.selectFirst("h2, h3")?.text()
                ?: item.selectFirst(".headline")?.text()
                ?: return@mapNotNull null

            val priceText = item.selectFirst("[data-testid=result-listing-price]")?.text()
                ?: item.selectFirst("[class*=price]")?.text()
                ?: return@mapNotNull null
            val price = Money.parse(priceText) ?: return@mapNotNull null

            val locationText = item.selectFirst("[data-testid=result-listing-location]")?.text()
                ?: item.selectFirst("[class*=location]")?.text()
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
