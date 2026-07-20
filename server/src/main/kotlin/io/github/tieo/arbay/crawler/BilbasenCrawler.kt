package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

/**
 * Crawler for bilbasen.dk, the Danish car marketplace. The search page (`/brugt/bil`)
 * sits behind an AWS WAF JS challenge, so every fetch runs through the headless-browser
 * fallback in [fetchWithFallback]. Once solved, the page is server-rendered (Next.js)
 * with listing cards straight in the HTML: `article.Listing_listing__*` wrapping a
 * make/model heading, a "kr." price, a description and a location line. CSS module
 * class suffixes are build-specific, so selectors match on the stable prefix only.
 */
class BilbasenCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.BILBASEN

    override suspend fun search(query: SearchQuery): List<Listing> {
        val searchText = CarQueryResolver.resolve(query.positiveText)?.let { car ->
            buildString {
                append(car.makeSlug.replace("-", " "))
                car.modelSlug?.let { append(' ').append(it) }
            }
        } ?: query.positiveText

        return paginate(query) { page ->
            val pageParam = if (page <= 1) "" else "&page=$page"
            val url = "https://www.bilbasen.dk/brugt/bil?free=${searchText.encodeUrl()}" +
                "&includeengroscvr=true&includeleasing=false${filterParams(query)}$pageParam"
            parse(fetchWithFallback(client, url, "Bilbasen", waitSelector = "article[class^=Listing_listing__]"))
        }
    }

    /**
     * Bilbasen URL filter parameters, verified live via /api/search/by-request on 2026-07-13.
     *
     * gear=automatic|manual      — transmission (UNVERIFIED on the SSR page directly; confirmed
     *                              via the internal search API which generates this URL param)
     * regfrom=YYYY-01            — first registration year lower bound (year, zero-padded month 01)
     * regto=YYYY-12              — first registration year upper bound (year, month 12)
     * mileageto=N                — mileage ceiling in km
     * priceto=N / pricefrom=N   — price bounds in DKK (integer, no decimals)
     * hpfrom=N                   — minimum engine power in horsepower (hestekræfter, not kW)
     *
     * Price is always DKK on bilbasen, so EUR amounts from SearchQuery are converted via
     * ExchangeRates. Power is always horsepower; 1 kW ≈ 1.341 hp so minPowerKw is multiplied.
     */
    private fun filterParams(query: SearchQuery): String = buildString {
        when (query.transmission) {
            Transmission.AUTOMATIC -> append("&gear=automatic")
            Transmission.MANUAL -> append("&gear=manual")
            null -> {}
        }
        query.firstRegFromYear?.let { append("&regfrom=${it}-01") }
        query.firstRegToYear?.let { append("&regto=${it}-12") }
        query.maxMileageKm?.let { append("&mileageto=$it") }
        query.maxPrice?.let { max ->
            val dkk = if (max.currency == Currency.DKK) max.amount / 100
            else ExchangeRates.convert(max.amount, max.currency.name, "DKK") / 100
            append("&priceto=$dkk")
        }
        query.minPrice?.let { min ->
            val dkk = if (min.currency == Currency.DKK) min.amount / 100
            else ExchangeRates.convert(min.amount, min.currency.name, "DKK") / 100
            append("&pricefrom=$dkk")
        }
        // Bilbasen filters by horsepower (hk), not kW. 1 kW = ~1.341 hp; round down to keep
        // the filter inclusive so cars at the boundary are not accidentally excluded.
        query.minPowerKw?.let { kw -> append("&hpfrom=${(kw * 1.341).toInt()}") }
    }

    internal fun parse(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        return doc.select("article[class^=Listing_listing__]").mapNotNull { article ->
            val href = article.selectFirst("a[class^=Listing_link__]")?.attr("href")
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = if (href.startsWith("http")) href else "https://www.bilbasen.dk$href"
            val externalId = url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return@mapNotNull null

            val title = article.selectFirst("div[class^=Listing_makeModel__]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            // Bilbasen only lists in Danish kroner, so the currency is forced rather
            // than trusted from the "kr." suffix. The trailing period in "kr." is
            // trimmed off first: Money.parse keeps literal dots, and a second one
            // after the thousands separator ("469.995.") breaks its double parse.
            val priceText = article.selectFirst("div[class^=Listing_price__] h3")?.text()
                ?.substringBefore("kr") ?: return@mapNotNull null
            val price = Money.parse(priceText, Currency.DKK) ?: return@mapNotNull null

            val imageUrl = article.selectFirst("img[data-nimg]")?.attr("src")?.takeIf { it.startsWith("http") }

            val city = article.selectFirst("div[class^=Listing_location__] span")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
            val location = Location(city = city, country = "DK")

            val description = article.selectFirst("div[class^=Listing_description__]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }

            // The whole card carries fuel/gearbox/km/power (in hk); parse the full text.
            val vehicle = VehicleTextParser.parse(article.text())

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                location = location,
                description = description,
                scrapedAt = now,
                vehicle = vehicle,
            )
        }.distinctBy { it.externalId }
    }
}
