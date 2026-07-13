package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class MobileDeCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.MOBILE_DE

    override suspend fun search(query: SearchQuery): List<Listing> {
        // mobile.de is behind Akamai Bot Manager. Only real Google Chrome driven by zendriver
        // (undetected CDP) under a headed display passes it; StealthBrowserClient runs that.
        // When the query resolves to a make and model, both vehicle categories are fetched,
        // because large vans (Crafter, Sprinter) can be listed under VanUpTo7500, not Car.
        val resolved = CarQueryResolver.resolve(query.positiveText)
        val categories = if (resolved != null) listOf("Car", "VanUpTo7500") else listOf("Car")
        val q = if (resolved != null) {
            listOfNotNull(resolved.makeSlug, resolved.modelSlug).joinToString(" ").encodeUrl()
        } else {
            query.positiveText.encodeUrl()
        }

        val maxPages = query.maxPages ?: CrawlerConfig.current.maxPages
        val seen = mutableSetOf<String>()
        return categories.flatMap { vc ->
            val url = "https://suchen.mobile.de/fahrzeuge/search.html?dam=0&isSearchRequest=true&s=Car&sb=rel&vc=$vc&q=$q"
            val pages = StealthBrowserClient.fetch(url, maxPages = maxPages)
            pages.split(StealthBrowserClient.PAGE_BREAK).flatMap { parseSearchResults(it) }
        }.filter { seen.add(it.externalId) }
    }

    // Result cards render with CSS-module hashed classes and stable data-testid values:
    // each listing container is `(top|base)-result-listing-N`, with `-title` and
    // `-price-section` descendants and a `/fahrzeuge/details` link.
    private val containerTestId = Regex("^(?:top|base)-result-listing-\\d+$")
    private val idInHref = Regex("""[?&]id=(\d+)""")
    private val firstPrice = Regex("""([0-9][0-9.]*)\s*€""")
    private val regInfo = Regex("""EZ\s*(\d{1,2}/\d{4})""")
    private val kmInfo = Regex("""([\d.]+)\s*km""")
    private val kwInfo = Regex("""(\d+)\s*kW""")

    internal fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        val items = doc.select("[data-testid]").filter { containerTestId.matches(it.attr("data-testid")) }

        return items.mapNotNull { item ->
            val href = item.selectFirst("a[href*=/fahrzeuge/details]")?.attr("href")
                ?: return@mapNotNull null
            val externalId = idInHref.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val url = if (href.startsWith("http")) href else "https://suchen.mobile.de$href"

            val title = item.selectFirst("[data-testid$=-title]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val priceText = item.selectFirst("[data-testid$=-price-section]")?.text()
                ?: item.selectFirst("[class*=Price],[class*=price]")?.text()
            val price = priceText?.let { firstPrice.find(it)?.value }?.let { Money.parse(it) }
                ?: return@mapNotNull null

            val info = item.text()
            val description = buildString {
                regInfo.find(info)?.let { append("EZ: ${it.groupValues[1]}") }
                kmInfo.find(info)?.let { if (isNotEmpty()) append(" | "); append("${it.groupValues[1]} km") }
                kwInfo.find(info)?.let { if (isNotEmpty()) append(" | "); append("${it.groupValues[1]} kW") }
            }.takeIf { it.isNotBlank() }

            val imageUrl = item.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("srcset").substringBefore(" ") }
            }?.takeIf { it.startsWith("http") }

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                location = Location(country = "DE"),
                description = description,
                scrapedAt = now,
            )
        }
    }
}
