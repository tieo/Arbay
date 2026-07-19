package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Crawler for finn.no, Norway's dominant marketplace and its main used-vehicle source.
 *
 * Each result is an `article` holding the title, a bullet spec row
 * ("2012 - 177 000 km - Diesel - Manuell") and the asking price in kroner. The spec row is the
 * site's own data rather than free prose, so its values are recorded as verified and may exclude
 * on a filter. Prices are NOK and converted for comparison by the shared exchange rates.
 */
class FinnCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.FINN

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        val text = if (car != null) {
            listOfNotNull(car.makeSlug.replace("-", " "), car.modelSlug).joinToString(" ")
        } else {
            query.positiveText
        }

        val maxPages = query.maxPages ?: CrawlerConfig.current.maxPages
        val seen = LinkedHashMap<String, Listing>()

        for (offset in 0 until maxPages) {
            val page = query.startPage + offset
            val base = "https://www.finn.no/mobility/search/car?q=${text.encodeUrl()}"
            val url = if (page <= 1) base else "$base&page=$page"

            val html = try {
                fetchWithFallback(client, url, "FINN")
            } catch (e: CrawlerBlockedException) {
                if (page == query.startPage) throw e
                break
            }

            val listings = parse(html)
            if (listings.isEmpty()) break
            val newIds = listings.count { it.externalId !in seen }
            listings.forEach { seen.putIfAbsent(it.externalId, it) }
            if (newIds == 0) break
            if (seen.size >= CrawlerConfig.current.maxResultsPerPlatform) break
        }

        return seen.values.toList()
    }

    internal fun parse(html: String): List<Listing> {
        val now = Clock.System.now()
        return Jsoup.parse(html).select("article").mapNotNull { card ->
            runCatching { parseCard(card, now) }.getOrNull()
        }.distinctBy { it.externalId }
    }

    private fun parseCard(card: Element, scrapedAt: kotlinx.datetime.Instant): Listing? {
        val href = card.selectFirst("a[href*=/mobility/item/]")?.attr("href") ?: return null
        val externalId = ID_REGEX.find(href)?.groupValues?.get(1) ?: return null
        val title = card.selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null

        // Digits are grouped with regular and non-breaking spaces ("126 296 kr", "177 000 km").
        val text = card.text().replace(' ', ' ')

        // A leasing card quotes a monthly instalment where a sale card quotes the asking price;
        // taking it as the price would advertise a van at a fraction of what it costs.
        if (MONTHLY_REGEX.containsMatchIn(text)) return null

        val kroner = PRICE_REGEX.find(text)?.groupValues?.get(1)?.filter { it.isDigit() }
            ?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val price = Money(kroner * 100, Currency.NOK)

        val structured = VehicleInfo(
            firstRegYear = YEAR_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1900..2100 },
            mileageKm = MILEAGE_REGEX.find(text)?.groupValues?.get(1)?.filter { it.isDigit() }
                ?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
            fuel = norwegianFuel(text),
            gearbox = norwegianGearbox(text),
        )

        val imageUrl = card.selectFirst("img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-src") }
        }?.takeIf { it.startsWith("http") }

        return Listing(
            id = "${platformId.name}:$externalId",
            platformId = platformId,
            externalId = externalId,
            url = if (href.startsWith("http")) href else "https://www.finn.no$href",
            title = title,
            price = price,
            imageUrls = listOfNotNull(imageUrl),
            location = Location(country = "NO"),
            // The card marks a private seller; anything else is a dealer.
            seller = if (text.contains("Privat", true)) {
                Seller(name = "Privat", type = SellerType.PRIVATE)
            } else null,
            scrapedAt = scrapedAt,
            vehicle = VehicleTextParser.verifiedByPresence(structured),
        )
    }

    /** The spec row states fuel and gearbox in Norwegian, which the shared parsers do not cover. */
    private fun norwegianFuel(text: String): Fuel? = when {
        text.contains("Diesel", true) -> Fuel.DIESEL
        text.contains("Bensin", true) -> Fuel.PETROL
        text.contains("Elektrisitet", true) || text.contains("Elektrisk", true) -> Fuel.ELECTRIC
        text.contains("Hybrid", true) -> Fuel.HYBRID_PETROL
        else -> null
    }

    private fun norwegianGearbox(text: String): Transmission? = when {
        text.contains("Automat", true) -> Transmission.AUTOMATIC
        text.contains("Manuell", true) -> Transmission.MANUAL
        else -> null
    }

    private companion object {
        val ID_REGEX = Regex("""/mobility/item/(\d+)""")
        val PRICE_REGEX = Regex("""([\d ]{4,})\s*kr\b""")
        val MONTHLY_REGEX = Regex("""kr\s*/\s*m(nd|åned)|pr\.?\s*m(nd|åned)|per\s*m(nd|åned)|leasing""", RegexOption.IGNORE_CASE)
        val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\s*[∙·•]""")
        val MILEAGE_REGEX = Regex("""([\d ]{3,})\s*km\b""")
    }
}
