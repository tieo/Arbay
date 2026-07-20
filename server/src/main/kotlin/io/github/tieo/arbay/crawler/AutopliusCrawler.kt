package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Crawler for autoplius.lt, Lithuania's largest vehicle marketplace.
 *
 * Results are server-rendered as `a.announcement-item` cards. Only the title carries a stable
 * class; the specs sit in nested unclassed nodes but always in the same order, so they are read
 * from the card's text: registration date, body type, gross price, fuel, gearbox, power, odometer.
 * Those values come from the site's own fields rather than free prose, so they are recorded as
 * verified and may exclude on a filter.
 */
class AutopliusCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.AUTOPLIUS

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        // The make/model path is what applies the filter; a free-text query has no equivalent here.
        val path = if (car != null) {
            buildString {
                append("/skelbimai/naudoti-automobiliai/").append(car.makeSlug)
                car.modelSlug?.let { append("/").append(it) }
            }
        } else {
            "/skelbimai/naudoti-automobiliai?keyword=${query.positiveText.encodeUrl()}"
        }

        return paginate(query) { page ->
            val sep = if ("?" in path) "&" else "?"
            val url = "https://autoplius.lt$path" + if (page <= 1) "" else "${sep}page_nr=$page"
            parse(fetchWithFallback(client, url, "Autoplius"))
        }
    }

    internal fun parse(html: String): List<Listing> {
        val now = Clock.System.now()
        return Jsoup.parse(html).select("a.announcement-item").mapNotNull { card ->
            runCatching { parseCard(card, now) }.getOrNull()
        }.distinctBy { it.externalId }
    }

    private fun parseCard(card: Element, scrapedAt: kotlinx.datetime.Instant): Listing? {
        val href = card.attr("href").takeIf { it.startsWith("http") } ?: return null
        val externalId = ID_REGEX.find(href)?.groupValues?.get(1) ?: return null
        val title = card.selectFirst(".announcement-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null

        // Digits are grouped with regular and non-breaking spaces ("10 829 €", "308 000 km").
        val text = card.text().replace(' ', ' ')

        // The gross asking price comes first; a net "be PVM" figure and a monthly financing
        // instalment may follow, and neither is what the buyer pays for the vehicle.
        val euros = PRICE_REGEX.find(text)?.groupValues?.get(1)?.filter { it.isDigit() }?.toLongOrNull()
            ?.takeIf { it > 0 } ?: return null
        val price = Money(euros * 100, Currency.EUR)

        val regDate = REG_DATE_REGEX.find(text)
        val structured = VehicleInfo(
            firstRegYear = regDate?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1900..2100 },
            firstRegMonth = regDate?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..12 },
            mileageKm = MILEAGE_REGEX.find(text)?.groupValues?.get(1)?.filter { it.isDigit() }
                ?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
            powerKw = POWER_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 20..1000 },
            fuel = lithuanianFuel(text),
            gearbox = lithuanianGearbox(text),
        )

        val imageUrl = card.selectFirst("img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-src") }
        }?.takeIf { it.startsWith("http") }

        return Listing(
            id = "${platformId.name}:$externalId",
            platformId = platformId,
            externalId = externalId,
            url = href,
            title = title,
            price = price,
            imageUrls = listOfNotNull(imageUrl),
            location = Location(country = "LT"),
            scrapedAt = scrapedAt,
            vehicle = VehicleTextParser.verifiedByPresence(structured),
        )
    }

    /** The site states fuel and gearbox in Lithuanian, which the shared parsers do not cover. */
    private fun lithuanianFuel(text: String): Fuel? = when {
        text.contains("Dyzelinas", true) -> Fuel.DIESEL
        text.contains("Benzinas", true) -> Fuel.PETROL
        text.contains("Elektra", true) -> Fuel.ELECTRIC
        text.contains("Hibridas", true) -> Fuel.HYBRID_PETROL
        text.contains("Dujos", true) -> Fuel.LPG
        else -> null
    }

    private fun lithuanianGearbox(text: String): Transmission? = when {
        text.contains("Automatinė", true) -> Transmission.AUTOMATIC
        text.contains("Mechaninė", true) -> Transmission.MANUAL
        else -> null
    }

    private companion object {
        val ID_REGEX = Regex("""-(\d+)\.html""")
        val PRICE_REGEX = Regex("""([\d ]{3,})\s*€""")
        val REG_DATE_REGEX = Regex("""\b(\d{4})-(\d{2})\b""")
        val MILEAGE_REGEX = Regex("""([\d ]{3,})\s*km\b""")
        val POWER_REGEX = Regex("""(\d{2,4})\s*kW\b""")
    }
}
