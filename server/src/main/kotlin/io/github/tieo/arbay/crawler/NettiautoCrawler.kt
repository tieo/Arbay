package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Crawler for nettiauto.com, Finland's largest vehicle marketplace.
 *
 * Results are server-rendered as `.product-card` elements. The card states its specs as one
 * bullet-separated row ("2013 - 131 300 km - Diesel - Manuaali - Takaveto") in
 * `.product-card__basic-info-list`, which is the site's own data rather than free prose, so the
 * values are recorded as verified and may exclude on a filter.
 */
class NettiautoCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.NETTIAUTO

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        // The make/model path applies the filter; anything else falls back to the site search.
        val path = if (car != null) {
            buildString {
                append("/").append(car.makeSlug)
                car.modelSlug?.let { append("/").append(it) }
            }
        } else {
            "/haku?search_type=1&free_word=${query.positiveText.encodeUrl()}"
        }

        return paginate(query) { page ->
            val sep = if ("?" in path) "&" else "?"
            val url = "https://www.nettiauto.com$path" + if (page <= 1) "" else "${sep}page=$page"
            parse(fetchWithFallback(client, url, "Nettiauto"))
        }
    }

    internal fun parse(html: String): List<Listing> {
        val now = Clock.System.now()
        return Jsoup.parse(html).select(".product-card").mapNotNull { card ->
            runCatching { parseCard(card, now) }.getOrNull()
        }.distinctBy { it.externalId }
    }

    private fun parseCard(card: Element, scrapedAt: kotlinx.datetime.Instant): Listing? {
        val href = card.selectFirst("a.product-card__image-link, a[href*=/]")?.attr("href")
            ?.takeIf { it.isNotBlank() } ?: return null
        val externalId = ID_REGEX.find(href)?.groupValues?.get(1) ?: return null
        val url = if (href.startsWith("http")) href else "https://www.nettiauto.com$href"

        val name = card.selectFirst(".product-card__title")?.text()?.trim().orEmpty()
        val variant = card.selectFirst(".product-card__sub-title")?.text()?.trim().orEmpty()
        val title = listOf(name, variant).filter { it.isNotBlank() }.joinToString(" ")
            .takeIf { it.isNotBlank() } ?: return null

        // Digits are grouped with non-breaking spaces ("15 900 €", "131 300 km").
        val priceText = card.selectFirst(".product-card__price-main")?.text()?.replace(' ', ' ')
        val euros = priceText?.let { PRICE_REGEX.find(it)?.groupValues?.get(1) }
            ?.filter { it.isDigit() }?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val price = Money(euros * 100, Currency.EUR)

        val specs = card.selectFirst(".product-card__basic-info-list")?.text()?.replace(' ', ' ').orEmpty()
        val structured = VehicleInfo(
            firstRegYear = YEAR_REGEX.find(specs)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1900..2100 },
            mileageKm = MILEAGE_REGEX.find(specs)?.groupValues?.get(1)?.filter { it.isDigit() }
                ?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
            fuel = finnishFuel(specs),
            gearbox = finnishGearbox(specs),
            drivetrain = finnishDrivetrain(specs),
        )

        val imageUrl = card.selectFirst("img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-src") }
        }?.takeIf { it.startsWith("http") }

        // The footer reads "Town, Dealer name"; only the town is a location.
        val town = card.selectFirst(".product-card__location-info")?.text()
            ?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }

        return Listing(
            id = "${platformId.name}:$externalId",
            platformId = platformId,
            externalId = externalId,
            url = url,
            title = title,
            price = price,
            imageUrls = listOfNotNull(imageUrl),
            location = Location(city = town, country = "FI"),
            scrapedAt = scrapedAt,
            vehicle = VehicleTextParser.verifiedByPresence(structured),
        )
    }

    /** The card states fuel, gearbox and drive in Finnish, which the shared parsers do not cover. */
    private fun finnishFuel(text: String): Fuel? = when {
        text.contains("Diesel", true) -> Fuel.DIESEL
        text.contains("Bensiini", true) -> Fuel.PETROL
        text.contains("Sähkö", true) -> Fuel.ELECTRIC
        text.contains("Hybridi", true) -> Fuel.HYBRID_PETROL
        else -> null
    }

    private fun finnishGearbox(text: String): Transmission? = when {
        text.contains("Automaatti", true) -> Transmission.AUTOMATIC
        text.contains("Manuaali", true) -> Transmission.MANUAL
        else -> null
    }

    private fun finnishDrivetrain(text: String): Drivetrain? = when {
        text.contains("Neliveto", true) -> Drivetrain.AWD
        text.contains("Takaveto", true) -> Drivetrain.RWD
        text.contains("Etuveto", true) -> Drivetrain.FWD
        else -> null
    }

    private companion object {
        val ID_REGEX = Regex("""/(\d{5,})(?:[/?#]|$)""")
        val PRICE_REGEX = Regex("""([\d ]{3,})\s*€""")
        val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
        val MILEAGE_REGEX = Regex("""([\d ]{3,})\s*km\b""")
    }
}
