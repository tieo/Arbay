package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

/**
 * Crawler for bytbil.com, a Swedish vehicle marketplace, restricted to its
 * van section (/transportbil). Results are server-rendered cards:
 * li.result-list-item wrapping a div[data-model-id] with the listing id,
 * a.js-link-target for title/href, span.car-price-main for the SEK price,
 * and a background-image style for the thumbnail.
 *
 * Filter parameters are ASP.NET MVC model-binding style dot-notation query params.
 * All are applied server-side and reduce the result set returned in the HTML:
 *   ModelYearRange.From / ModelYearRange.To — registration year
 *   MilageRange.To                          — in Swedish mil (1 mil = 10 km)
 *   PriceRange.From / PriceRange.To         — in SEK
 *   EnginePowerRange.From                   — in HP (hk); 1 kW ≈ 1.36 HP
 *   Gearboxes                               — "Manuell" or "Automatisk"
 *
 * Mileage is in Swedish mil on the site. The converter divides km by 10 and rounds up
 * to the nearest whole mil so the ceiling is inclusive.
 */
class BytbilCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.BYTBIL

    companion object {
        /** kW to metric horsepower (1 kW = 1.35962 PS). Bytbil uses HP (hk). */
        private fun kwToHp(kw: Int): Int = (kw * 1.35962).toInt()

        /** km to Swedish mil, rounded up so the ceiling is inclusive. */
        private fun kmToMil(km: Int): Int = (km + 9) / 10

        /** Extracts the URL from a CSS background-image declaration, quotes included. */
        private val CSS_URL_REGEX = Regex("""url\(([^)]+)\)""")
    }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)

        val basePath = buildString {
            append("https://www.bytbil.com/transportbil?")
            if (car != null) {
                append("Makes%5B0%5D=").append(car.makeSlug.encodeUrl())
                car.modelSlug?.let { append("&FreeText=").append(it.encodeUrl()) }
            } else {
                append("FreeText=").append(query.positiveText.encodeUrl())
            }
            append(filterParams(query))
        }

        return paginate(query) { page ->
            parse(fetchWithFallback(client, "$basePath&Page=$page", "Bytbil"))
        }
    }

    /**
     * Appends verified bytbil filter params to the base URL query string.
     * All params reduce the server-side result set — no client-side post-filtering needed.
     */
    private fun filterParams(query: SearchQuery): String = buildString {
        query.carCriteria.firstRegFromYear?.let { append("&ModelYearRange.From=$it") }
        query.carCriteria.firstRegToYear?.let { append("&ModelYearRange.To=$it") }
        query.carCriteria.maxMileageKm?.let { append("&MilageRange.To=${kmToMil(it)}") }
        query.maxPrice?.let { max ->
            val sek = if (max.currency == Currency.SEK) max.amount / 100
            else ExchangeRates.convert(max.amount, max.currency.name, "SEK") / 100
            append("&PriceRange.To=$sek")
        }
        query.minPrice?.let { min ->
            val sek = if (min.currency == Currency.SEK) min.amount / 100
            else ExchangeRates.convert(min.amount, min.currency.name, "SEK") / 100
            append("&PriceRange.From=$sek")
        }
        query.carCriteria.minPowerKw?.let { append("&EnginePowerRange.From=${kwToHp(it)}") }
        when (query.carCriteria.transmission) {
            Transmission.AUTOMATIC -> append("&Gearboxes=Automatisk")
            Transmission.MANUAL -> append("&Gearboxes=Manuell")
            null -> {}
        }
    }

    internal fun parse(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        return doc.select("li.result-list-item div[data-model-id]").mapNotNull { card ->
            val externalId = card.attr("data-model-id").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val link = card.selectFirst("a.js-link-target") ?: return@mapNotNull null
            val title = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = if (href.startsWith("http")) href else "https://www.bytbil.com$href"

            val priceText = card.selectFirst("span.car-price-main")?.text()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val price = Money.parse(priceText, Currency.SEK) ?: return@mapNotNull null

            // The single truncated <p> after the title holds "year | mileage | place",
            // separated by vertical-divider spans; "place" is either a zip or a town name.
            val infoParts = card.selectFirst("p.uk-text-truncate")
                ?.text()
                ?.split("|")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val year = infoParts.getOrNull(0)
            val mileage = infoParts.getOrNull(1)
            val place = infoParts.getOrNull(2)

            val description = buildString {
                year?.let { append(it) }
                mileage?.let {
                    if (isNotEmpty()) append(" | ")
                    append(it)
                }
            }.takeIf { it.isNotBlank() }

            val imageStyle = card.selectFirst("div.car-image")?.attr("style")
            val imageUrl = imageStyle?.let { CSS_URL_REGEX.find(it)?.groupValues?.get(1) }
                ?.trim('\'', '"')
                ?.takeIf { it.startsWith("http") }

            // Year is the structured first info part; fuel/gearbox/power come from the card text.
            val vehicle = VehicleTextParser.merge(
                VehicleTextParser.verifiedByPresence(
                    VehicleInfo(firstRegYear = year?.toIntOrNull()?.takeIf { it in 1980..2035 }),
                ),
                VehicleTextParser.parse(card.text()),
            )

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                location = place?.let { Location(city = it, country = "SE") },
                description = description,
                scrapedAt = now,
                vehicle = vehicle,
            )
        }.distinctBy { it.externalId }
    }
}
