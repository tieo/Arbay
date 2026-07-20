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
        val platform = platformId.displayName
        val seen = mutableSetOf<String>()
        val all = mutableListOf<Listing>()

        for (vc in categories) {
            // mobile.de bypasses fetchWithFallback (dedicated stealth browser), so instrument and
            // enforce the request cutoff here too — it's the most block-sensitive platform.
            if (RequestMonitor.overBudget(platform))
                throw CrawlerBlockedException("$platform: request cutoff reached, skipping", ErrorType.RATE_LIMITED_429)
            val url = "https://suchen.mobile.de/fahrzeuge/search.html?dam=0&isSearchRequest=true&s=Car&sb=rel&vc=$vc&q=$q"
            RequestMonitor.recordTier(platform, "Browser")
            try {
                // Each page streams in as the stealth browser loads it; parse and surface it live
                // instead of blocking ~90s on the whole multi-page, multi-category crawl.
                StealthBrowserClient.fetchStreaming(url, maxPages = maxPages) { html ->
                    RequestMonitor.recordRequest(platform)
                    val fresh = parseSearchResults(html).filter { seen.add(it.externalId) }
                    emitPartialResults(fresh)
                    all.addAll(fresh)
                }
            } catch (e: CrawlerBlockedException) {
                // A block with nothing collected yet is a real failure; otherwise keep what streamed
                // in — e.g. the Car category returned before the Van category got challenged.
                if (all.isEmpty()) throw e
            }
        }
        return all
    }

    // Result cards render with CSS-module hashed classes and stable data-testid values: each
    // listing container is `(top|base)-result-listing-N`, holding a `listing-title-card-view`
    // title, a `main-price-label` price and a `/fahrzeuge/details` link.
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

            // `listing-title-card-view` is the bare model name; the `-title` wrapper also holds a
            // "Gesponsert" sponsored badge and a subtitle, which would leak into the title text.
            val title = (item.selectFirst("[data-testid=listing-title-card-view]")
                ?: item.selectFirst("[data-testid$=-title]"))?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            // Price lives in `main-price-label` ("42.900 €"); `price-label` is the same amount
            // elsewhere in the card, `-price-section` is the legacy testid. nbsp (U+00A0) between
            // the number and € would defeat the \s* in firstPrice, so normalise it to a space.
            val priceText = (item.selectFirst("[data-testid=main-price-label]")
                ?: item.selectFirst("[data-testid=price-label]")
                ?: item.selectFirst("[data-testid$=-price-section]")
                ?: item.selectFirst("[class*=Price],[class*=price]"))?.text()?.replace('\u00a0', ' ')
            val price = priceText?.let { firstPrice.find(it)?.value }?.let { Money.parse(it) }
                ?: return@mapNotNull null

            val info = item.text().replace('\u00a0', ' ')
            val reg = regInfo.find(info)?.groupValues?.get(1)  // "MM/YYYY"
            val description = buildString {
                reg?.let { append("EZ: $it") }
                kmInfo.find(info)?.let { if (isNotEmpty()) append(" | "); append("${it.groupValues[1]} km") }
                kwInfo.find(info)?.let { if (isNotEmpty()) append(" | "); append("${it.groupValues[1]} kW") }
            }.takeIf { it.isNotBlank() }

            // The card text carries EZ/km/kW precisely; fuel, gearbox and body come from the
            // rest of the same text via the shared parser.
            val vehicle = VehicleTextParser.merge(
                VehicleTextParser.verifiedByPresence(VehicleInfo(
                    firstRegYear = reg?.substringAfter("/")?.toIntOrNull(),
                    firstRegMonth = reg?.substringBefore("/")?.toIntOrNull(),
                    mileageKm = kmInfo.find(info)?.groupValues?.get(1)?.replace(".", "")?.toIntOrNull()
                        ?.takeIf { it in 1..2_000_000 },
                    powerKw = kwInfo.find(info)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 20..1000 },
                )),
                VehicleTextParser.parse(info),
            )

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
                vehicle = vehicle,
            )
        }
    }
}
