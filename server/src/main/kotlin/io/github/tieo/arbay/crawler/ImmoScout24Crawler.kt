package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class ImmoScout24Crawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.IMMOSCOUT24

    override suspend fun search(query: SearchQuery): List<Listing> {
        val url = "https://www.immobilienscout24.de/Suche/de/wohnung-mieten?enteredFrom=result_list&searchQuery=${query.positiveText.encodeUrl()}"
        // The generic HTTP/curl_cffi/Playwright-Chromium/Firefox tiers all get detected here
        // (401s and timeouts across the board) — the page itself is not actually behind a hard
        // block, since the zendriver real-Chrome stealth tier loads it cleanly (verified live: real
        // title, real listing cards, no captcha/access-denied text). Go straight to it rather than
        // burning through four tiers known to fail first. "headline" is the card title's own
        // data-testid — unlike "/expose/" (also a substring of a CSS background-image path,
        // matching before any real card has rendered), it appears only inside an actual card.
        val html = StealthBrowserClient.fetchRendered(url, waitMarker = "data-testid=\"headline\"", waitSeconds = 25, minMatches = 3)
        return parseSearchResults(html)
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // Each result card is a [data-obid] element (verified live 2026-08-31 — the site no
        // longer uses data-testid=result-list-entry or a result-list__listing class at all).
        val items = doc.select("[data-obid]")

        return items.mapNotNull { item ->
            val href = item.selectFirst("a[href*='/expose/']")?.attr("href") ?: return@mapNotNull null
            val url = if (href.startsWith("http")) href else "https://www.immobilienscout24.de$href"

            val externalId = item.attr("data-obid").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val title = item.selectFirst("[data-testid=headline]")?.text() ?: return@mapNotNull null

            // The attributes block lists price, area and room count as sibling <dd>s in that
            // order; the price is always first.
            val priceText = item.selectFirst("[data-testid=attributes] dd")?.text() ?: return@mapNotNull null
            val price = Money.parse(priceText) ?: return@mapNotNull null

            val locationText = item.selectFirst("[data-testid=hybridViewAddress]")?.text()
            val location = locationText?.let { Location.parse(it) }

            // Every slide but the first is lazy-loaded behind a 1x1 placeholder in src, the real
            // URL sitting in data-lazy-src until it scrolls into view.
            val imageUrl = item.selectFirst("img.gallery__image")?.let {
                it.attr("data-lazy-src").ifBlank { it.attr("src") }
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
