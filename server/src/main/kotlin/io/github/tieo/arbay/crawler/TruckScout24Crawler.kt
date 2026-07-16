package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

/**
 * Crawler for truckscout24.de used transporters. Results are server-rendered
 * cards: section[data-listing-id] with data-grid slots for title, price and
 * location, and detail links under /tsp/.
 */
class TruckScout24Crawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.TRUCKSCOUT24

    override suspend fun search(query: SearchQuery): List<Listing> {
        // category-ids=249 is the light van/truck category ("Transporter & LKW bis 7,5t").
        // The site's own filters live in manufacturers= and models=, which expect the
        // display name ("Volkswagen", "Mercedes-Benz", "Crafter"); the makeModel= parameter
        // is silently ignored and returns the whole category.
        //
        // Vehicle filters (year, mileage, power, gearbox) use PHP bracket notation that the
        // /transporter/gebraucht path rejects with HTTP 500. The /main/search/index path
        // accepts all params including properties[], so it is used whenever any filter is set.
        val car = CarQueryResolver.resolve(query.positiveText)
        val hasFilters = filterParams(query).isNotEmpty()
        val basePath = if (hasFilters)
            "https://www.truckscout24.de/main/search/index"
        else
            "https://www.truckscout24.de/transporter/gebraucht"
        val url = buildString {
            append(basePath)
            append("?category-ids=249")
            if (car != null) {
                append("&manufacturers=").append(displayName(car.makeSlug).encodeUrl())
                car.modelSlug?.let { append("&models=").append(displayName(it).encodeUrl()) }
            }
            append(filterParams(query))
        }
        val html = fetchWithFallback(client, url, "TruckScout24")
        return parse(html)
    }

    /** Turns a lowercase slug into the display name the site's filters expect:
     *  "volkswagen" to "Volkswagen", "mercedes-benz" to "Mercedes-Benz". */
    private fun displayName(slug: String): String =
        slug.split("-").joinToString("-") { it.replaceFirstChar(Char::uppercase) }

    /**
     * TruckScout24 URL filter parameters, verified live via server-state JSON on 2026-07-13.
     *
     * manufacturedFrom=YYYY / manufacturedTo=YYYY — build year bounds (integer year)
     * price-to=N / price-from=N                  — price bounds in EUR (integer)
     * properties[mileage][value to]=N             — mileage ceiling in km
     * properties[power][value from]=N             — minimum engine power in kW
     * properties[gearing type][value]=automatic|mechanical — transmission
     *
     * These properties[] params are accepted only by /main/search/index, not by
     * /transporter/gebraucht (returns HTTP 500). The search() method switches base path
     * when any filter is present.
     */
    private fun filterParams(query: SearchQuery): String = buildString {
        query.firstRegFromYear?.let { append("&manufacturedFrom=$it") }
        query.firstRegToYear?.let { append("&manufacturedTo=$it") }
        query.maxPrice?.let { max ->
            val eur = if (max.currency == Currency.EUR) max.amount / 100
            else ExchangeRates.convert(max.amount, max.currency.name, "EUR") / 100
            append("&price-to=$eur")
        }
        query.minPrice?.let { min ->
            val eur = if (min.currency == Currency.EUR) min.amount / 100
            else ExchangeRates.convert(min.amount, min.currency.name, "EUR") / 100
            append("&price-from=$eur")
        }
        // PHP bracket notation: properties[mileage][value to] and properties[power][value from].
        // Spaces in param names are encoded as + by standard form encoding.
        query.maxMileageKm?.let { append("&properties%5Bmileage%5D%5Bvalue+to%5D=$it") }
        query.minPowerKw?.let { append("&properties%5Bpower%5D%5Bvalue+from%5D=$it") }
        when (query.transmission) {
            Transmission.AUTOMATIC -> append("&properties%5Bgearing+type%5D%5Bvalue%5D=automatic")
            Transmission.MANUAL -> append("&properties%5Bgearing+type%5D%5Bvalue%5D=mechanical")
            null -> {}
        }
    }

    internal fun parse(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()
        return doc.select("section[data-listing-id]").mapNotNull { card ->
            val externalId = card.attr("data-listing-id").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = card.selectFirst("[data-grid=title]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // The price cell can carry both the net and the gross figure ("19.900 € 23.681 €");
            // text() would merge their digits into one absurd number, so take the first amount
            // (the headline price the site shows). TruckScout24 prices are all EUR.
            val priceText = card.selectFirst("[data-grid=price]")?.text()
                ?: return@mapNotNull null
            val firstAmount = Regex("""\d[\d.,]*""").find(priceText)?.value
                ?: return@mapNotNull null
            val price = Money.parse(firstAmount, Currency.EUR) ?: return@mapNotNull null
            val href = card.selectFirst("a[href^=/tsp/]")?.attr("href")
            val url = if (href.isNullOrBlank()) {
                "https://www.truckscout24.de"
            } else {
                "https://www.truckscout24.de$href"
            }
            val locationText = card.selectFirst("[data-grid=location]")?.text()?.trim()
            val imageUrl = card.selectFirst("[data-grid=image] img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.takeIf { it.startsWith("http") }

            // The card carries a labeled attribute line: "<km> km, Leistung: <kW> kW (<PS> PS),
            // Erstzulassung: MM/YYYY, Kraftstofftyp: <fuel>, Getriebetyp: <gearbox>".
            val cardText = card.text()
            val reg = Regex("""Erstzulassung:\s*(\d{1,2}/\d{4})""").find(cardText)?.groupValues?.get(1)
            val vehicle = VehicleTextParser.merge(
                VehicleTextParser.verifiedByPresence(VehicleInfo(
                    firstRegYear = reg?.substringAfter("/")?.toIntOrNull(),
                    firstRegMonth = reg?.substringBefore("/")?.toIntOrNull(),
                    mileageKm = Regex("""([\d.]+)\s*km,\s*Leistung""").find(cardText)
                        ?.groupValues?.get(1)?.replace(".", "")?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
                    powerKw = Regex("""Leistung:\s*(\d+)\s*kW""").find(cardText)
                        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 20..1000 },
                    fuel = Fuel.parse(Regex("""Kraftstofftyp:\s*([^,]+)""").find(cardText)?.groupValues?.get(1)),
                    gearbox = when {
                        Regex("""Getriebetyp:\s*(mechani|schaltg|manu)""", RegexOption.IGNORE_CASE).containsMatchIn(cardText) -> Transmission.MANUAL
                        Regex("""Getriebetyp:\s*(automat)""", RegexOption.IGNORE_CASE).containsMatchIn(cardText) -> Transmission.AUTOMATIC
                        else -> null
                    },
                )),
                VehicleTextParser.parse(cardText),
            )

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                location = locationText?.takeIf { it.isNotBlank() }?.let { Location(city = it) },
                description = null,
                scrapedAt = now,
                vehicle = vehicle,
            )
        }.distinctBy { it.externalId }
    }
}
