package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import org.jsoup.Jsoup

/**
 * Crawler for truckscout24.de used transporters. Results are server-rendered
 * cards: section[data-listing-id] with data-grid slots for title, price and
 * location, and detail links under /tsp/.
 */
class TruckScout24Crawler(private val client: HttpClient) : Crawler, FiltersAtTheSource, KnowsLocation, FetchesEveryPage, SortsByPrice {
    override val nativeCriteria = setOf(FiltersAtTheSource.Criterion.PRICE)

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
        val car = CarQueryResolver.resolveForCarSite(query.positiveText)
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
            // Cheapest first, by the headline price the site caps at (net for a dealer).
            append("&sort-field=preis&sort-direction=asc")
        }
        // Twenty-five vans to a page. A page past the last answers 404, so a page short of full is
        // the last one asked for.
        var lastWasFull = true
        return paginate(query.wholePriceBand()) { page ->
            if (!lastWasFull) return@paginate emptyList()
            parse(fetchWithFallback(client, if (page <= 1) url else "$url&page=$page", "TruckScout24"))
                .also { lastWasFull = it.size >= PAGE_SIZE }
        }
    }

    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        parseDetail(fetchWithFallback(client, listing.url, "TruckScout24"))
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        null
    }

    /**
     * An ad's page: the dealer's text under "Beschreibung", one line per `<br>`, and the site's
     * technical rows ("Laderaumlänge: 3.300 mm", "Kilometerstand: 150.000 km"). The rows are
     * values the site states, so they are taken as such; from the prose only the wheelbase is
     * read, which no row carries. The load space measures are kept as a line of the description,
     * since they tell an L2 from an L3 where a title only claims one.
     */
    internal fun parseDetail(html: String): ListingDetail? {
        val doc = Jsoup.parse(html)
        val text = doc.selectFirst(".beschreibung")?.let { body ->
            // The page's own line breaks are layout; its <br>s are the dealer's lines.
            body.html().split(Regex("""<br\s*/?>""")).joinToString("\n") { Jsoup.parse(it).text().trim() }
                .replace(Regex("""\n{3,}"""), "\n\n").trim()
        }?.takeIf { it.isNotBlank() }
        val rows = doc.select(".technical-details-row").mapNotNull { row ->
            val spans = row.select("span")
            if (spans.size < 2) return@mapNotNull null
            spans[0].text().trim().removeSuffix(":").trim() to spans[1].text().trim()
        }.filter { (label, value) -> label.isNotBlank() && value.isNotBlank() }.toMap()
        fun number(label: String) = rows[label]?.let { Regex("""\d[\d.]*""").find(it)?.value?.replace(".", "")?.toIntOrNull() }
        val reg = rows["Erstzulassung"]?.let { Regex("""(\d{1,2})/(\d{4})""").find(it) }
        val stated = VehicleTextParser.verifiedByPresence(VehicleInfo(
            firstRegYear = reg?.groupValues?.get(2)?.toIntOrNull(),
            firstRegMonth = reg?.groupValues?.get(1)?.toIntOrNull(),
            mileageKm = number("Kilometerstand")?.takeIf { it in 1..2_000_000 },
            powerKw = number("Leistung")?.takeIf { it in 20..1000 },
            fuel = Fuel.parse(rows["Kraftstofftyp"]),
            gearbox = rows["Getriebetyp"]?.lowercase()?.let {
                when {
                    it.startsWith("automat") -> Transmission.AUTOMATIC
                    it.startsWith("mechani") || it.startsWith("schalt") || it.startsWith("manu") -> Transmission.MANUAL
                    else -> null
                }
            },
            seats = number("Anzahl der Sitzplätze")?.takeIf { it in 1..20 },
            color = rows["Farbe"],
            emissionClassEuro = rows["Emissionsklasse"]?.let { Regex("""euro\s*(\d)""", RegexOption.IGNORE_CASE).find(it) }
                ?.groupValues?.get(1)?.toIntOrNull(),
            wheelbaseMm = text?.let(VehicleTextParser::parseWheelbaseMm),
        ))
        val loadSpace = listOf("Laderaumlänge", "Laderaumbreite", "Laderaumhöhe", "Gesamtgewicht")
            .mapNotNull { label -> rows[label]?.let { "$label: $it" } }
            .joinToString(", ").takeIf { it.isNotBlank() }
        val description = listOfNotNull(text, loadSpace).joinToString("\n\n").takeIf { it.isNotBlank() }
        return ListingDetail(vehicle = stated.takeIf { it.verified.isNotEmpty() }, description = description)
            .takeIf { it.vehicle != null || it.description != null }
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
    internal fun filterParams(query: SearchQuery): String = buildString {
        // The build-year parameters are not usable: `manufacturedFrom` does not filter at all —
        // 2018 and 2030 both answer with the same 248 of 521 vans, which is every van that states
        // a year — and `manufacturedTo` answers 0 for any value, which is what made this market
        // look like it had nothing. The year is filtered locally.
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
        val c = query.carCriteria.atTheSource()
        // Two of 521 vans state no mileage and seven no fuel, which the site's filter would delete:
        // those go only with a strict search, like the power and the gearbox.
        if (c.strictUnknown) {
            c.minMileageKm?.let { append("&properties%5Bmileage%5D%5Bvalue+from%5D=$it") }
            c.maxMileageKm?.let { append("&properties%5Bmileage%5D%5Bvalue+to%5D=$it") }
            c.minPowerKw?.let { append("&properties%5Bpower%5D%5Bvalue+from%5D=$it") }
            c.maxPowerKw?.let { append("&properties%5Bpower%5D%5Bvalue+to%5D=$it") }
        }
        // The gearbox is stated by 358 of 521 vans here, so asking the site for it deletes a third
        // of the market — only worth it when the search excludes unstated specs anyway.
        if (query.carCriteria.strictUnknown) when (query.carCriteria.transmission) {
            Transmission.AUTOMATIC -> append("&properties%5Bgearing+type%5D%5Bvalue%5D=automatic")
            Transmission.MANUAL -> append("&properties%5Bgearing+type%5D%5Bvalue%5D=mechanical")
            null -> {}
        }
        // A site's own filter removes every ad that states nothing for the field, which is the
        // opposite of this app's rule that an unstated spec keeps the listing and marks it
        // unchecked. So a criterion is sent to the site only where the site's ads nearly all state
        // it, measured by asking for a range that excludes nothing: of 521 Crafters here, 519
        // state a mileage and 514 a fuel, so those are asked at the source; 477 state a seat
        // count, 430 an emission class, and only 2 a wheelbase — those are filtered here, where
        // not stating one is not the same as failing it.
        if (c.strictUnknown) c.fuels.mapNotNull { siteFuel(it) }.distinct()
            .forEach { append("&properties%5Bfuel+type%5D%5B%5D=$it") }
    }

    /** This site's own fuel words. */
    private fun siteFuel(fuel: Fuel): String? = when (fuel) {
        Fuel.DIESEL -> "diesel"
        Fuel.PETROL -> "gasoline"
        Fuel.ELECTRIC -> "electric"
        Fuel.LPG, Fuel.CNG -> "gas"
        Fuel.HYBRID_PETROL, Fuel.HYBRID_DIESEL, Fuel.PLUGIN_HYBRID, Fuel.MILD_HYBRID -> "hybrid"
        Fuel.HYDROGEN -> "hydrogen"
        else -> null
    }

    internal fun parse(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()
        // A search with no results still paints a page full of vans — the site's own suggestions,
        // in the same cards as a result — and every one of them was being read as a result of the
        // search. The page states its own count in the search-agent form, so a search that found
        // nothing returns nothing.
        val ownCount = doc.selectFirst("input[name=\"SearchAgentForm[initialResultCount]\"]")
            ?.attr("value")?.trim()?.toIntOrNull()
        if (ownCount == 0) return emptyList()
        return doc.select("section[data-listing-id]").mapNotNull { card ->
            val externalId = card.attr("data-listing-id").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val title = card.selectFirst("[data-grid=title]")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // A dealer's price cell reads "14.900 € Festpreis zzgl. MwSt. (17.731 € brutto)": the
            // headline is net, and a private buyer pays the gross, so the gross is the price where
            // the cell states one, else the only amount there is. TruckScout24 prices are all EUR.
            val priceText = card.selectFirst("[data-grid=price]")?.text()
                ?: return@mapNotNull null
            val amount = Regex("""(\d[\d.,]*)\s*€?\s*brutto""", RegexOption.IGNORE_CASE).find(priceText)?.groupValues?.get(1)
                ?: Regex("""\d[\d.,]*""").find(priceText)?.value
                ?: return@mapNotNull null
            val price = Money.parse(amount, Currency.EUR) ?: return@mapNotNull null
            val href = card.selectFirst("a[href^=/tsp/]")?.attr("href")
            val url = if (href.isNullOrBlank()) {
                "https://www.truckscout24.de"
            } else {
                "https://www.truckscout24.de$href"
            }
            // The cell holds the town, a flag naming the country ("flag24-de") and the site's own
            // distance from the reader ("393 km"), which made the whole cell no place at all.
            val locationCell = card.selectFirst("[data-grid=location]")
            val locationText = (locationCell?.selectFirst(".country-name") ?: locationCell)?.text()?.trim()
            val country = locationCell?.selectFirst("img[class*=flag24-]")?.classNames()
                ?.firstNotNullOfOrNull { Regex("""^flag24-([a-z]{2})$""").find(it)?.groupValues?.get(1)?.uppercase() }
            val imageUrl = card.selectFirst("[data-grid=image] img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.takeIf { it.startsWith("http") }

            // The card carries a labeled attribute line: "<km> km, Leistung: <kW> kW (<PS> PS),
            // Erstzulassung: MM/YYYY, Kraftstofftyp: <fuel>, Getriebetyp: <gearbox>". Read without
            // the location cell, whose distance from the reader ("Parma 400 km") read as the
            // mileage of a Crafter whose seller states 97,967 km.
            val cardText = card.clone().also { it.select("[data-grid=location]").remove() }.text()
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
                location = locationText?.takeIf { it.isNotBlank() }?.let { Location(city = it, country = country) },
                description = null,
                scrapedAt = now,
                vehicle = vehicle,
            )
        }.distinctBy { it.externalId }
    }

    private companion object {
        /** Vans on one page of the site's results. */
        const val PAGE_SIZE = 25
    }
}
