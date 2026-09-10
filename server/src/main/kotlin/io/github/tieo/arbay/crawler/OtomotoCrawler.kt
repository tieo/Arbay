package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/**
 * Crawler for otomoto.pl, Poland's largest car marketplace. The site is a Next.js app
 * whose listings never touch the DOM as plain HTML: they arrive inside a urql (GraphQL
 * client) cache embedded in the `__NEXT_DATA__` script. One of the cache entries decodes
 * to an `advertSearch` object with `edges[].node` holding title, price, location and a
 * free-form `parameters` array (mileage, year, fuel type, ...).
 *
 * The `/osobowe` (passenger cars) category applies the make/model path segments as a real
 * filter. `/dostawcze` (vans/light trucks) accepts the same path but silently drops the
 * model segment and falls back to listing every van of that make, so `/osobowe` is used
 * even for commercial models such as the VW Crafter or Mercedes Sprinter, which otomoto
 * still lists there with a working model filter.
 */
class OtomotoCrawler(
    private val client: HttpClient,
    override val platformId: PlatformId = PlatformId.OTOMOTO,
    private val host: String = "https://www.otomoto.pl",
    private val categoryPath: String = "osobowe",
    // Currency the site quotes prices and applies its price filter in. Read per-listing from the
    // node's currencyCode where present; this is the fallback and the price-filter unit.
    private val siteCurrency: Currency = Currency.PLN,
    private val countryCode: String = "PL",
    private val siteLabel: String = "OTOMoto",
) : Crawler, FetchesEveryPage, FiltersAtTheSource, KnowsLocation {
    override val nativeCriteria = setOf(FiltersAtTheSource.Criterion.YEAR, FiltersAtTheSource.Criterion.MILEAGE, FiltersAtTheSource.Criterion.PRICE, FiltersAtTheSource.Criterion.POWER, FiltersAtTheSource.Criterion.GEARBOX)


    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        // The make and model are the whole filter here: without them the URL is the category's
        // front page, which answers every query with the same catalogue. Nothing to ask, so
        // nothing is asked.
        val carQuery = CarQueryResolver.resolveForCarSite(query.positiveText) ?: return emptyList()

        // Where to look is a path segment here, with the distance as a parameter beside it:
        // /osobowe/volkswagen/crafter/warszawa?search[dist]=50. Verified live — 31 offers
        // nationwide against 20 within 50 km of Warsaw. A place this country does not know
        // resolves to nothing, so a German town never reaches a Polish URL.
        val area = query.area(countryCode)
        val citySlug = area?.placeName?.let(::citySlug)

        val basePath = buildString {
            append("$host/$categoryPath")
            append("/").append(carQuery.makeSlug)
            carQuery.modelSlug?.let { append("/").append(it) }
            citySlug?.let { append("/").append(it) }
        }

        val filters = buildString {
            append(filterParams(query))
            if (citySlug != null && area != null) {
                if (isNotEmpty()) append("&")
                append("search%5Bdist%5D=${area.radiusKm}")
            }
        }
        return paginate(query) { page ->
            // Page 1 uses no page param; subsequent pages append &page=N after the filter params.
            val url = if (page <= 1) {
                if (filters.isEmpty()) basePath else "$basePath?$filters"
            } else {
                if (filters.isEmpty()) "$basePath?page=$page" else "$basePath?$filters&page=$page"
            }
            parse(fetchWithFallback(client, url, siteLabel))
        }
    }

    /** A town as this site writes it in a URL: lowercase, its diacritics folded, spaces joined. */
    private fun citySlug(place: String): String? = place.trim().lowercase()
        .replace("ą", "a").replace("ć", "c").replace("ę", "e").replace("ł", "l")
        .replace("ń", "n").replace("ó", "o").replace("ś", "s").replace("ź", "z").replace("ż", "z")
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')
        .takeIf { it.length >= 3 }

    /**
     * Builds the otomoto query-string fragment (without a leading "?") for the vehicle
     * filters supported by SearchQuery. The fragment is empty when no filters are set.
     *
     * Parameter names verified live against otomoto.pl by measuring result-count drops:
     *   search[filter_float_year:from]          -- first-registration year, lower bound
     *   search[filter_float_year:to]            -- first-registration year, upper bound
     *   search[filter_float_mileage:to]         -- odometer ceiling in km
     *   search[filter_float_price:from]         -- price floor in PLN (otomoto's native currency)
     *   search[filter_float_price:to]           -- price ceiling in PLN
     *   search[filter_float_engine_power:from]  -- minimum power in KM (metric horsepower / PS)
     *   search[filter_enum_gearbox][0]          -- "automatic" or "manual"
     *
     * Power unit: otomoto uses KM (Polish: konie mechaniczne = metric horsepower / PS).
     * The conversion factor is 1 kW = 1.35962 KM; round to the nearest integer.
     *
     * Price unit: otomoto prices are in PLN. EUR amounts from SearchQuery are converted
     * using the live ExchangeRates table before appending.
     *
     * Parameter names use percent-encoded brackets (%5B / %5D) because otomoto returns
     * no __NEXT_DATA__ when raw "[" / "]" appear in the query string.
     */
    private fun filterParams(query: SearchQuery): String = buildString {
        fun enc(key: String, value: String) {
            if (isNotEmpty()) append("&")
            // Encode each bracket pair; colons and alphanumerics are left as-is.
            append(key
                .replace("[", "%5B")
                .replace("]", "%5D")
                .replace(":", "%3A")
            )
            append("=").append(value)
        }

        query.carCriteria.firstRegFromYear?.let { enc("search[filter_float_year:from]", it.toString()) }
        query.carCriteria.firstRegToYear?.let { enc("search[filter_float_year:to]", it.toString()) }
        query.carCriteria.maxMileageKm?.let { enc("search[filter_float_mileage:to]", it.toString()) }

        query.minPrice?.let { min ->
            val cents = if (min.currency == siteCurrency) min.amount
            else ExchangeRates.convert(min.amount, min.currency.name, siteCurrency.name)
            enc("search[filter_float_price:from]", (cents / 100).toString())
        }
        query.maxPrice?.let { max ->
            val cents = if (max.currency == siteCurrency) max.amount
            else ExchangeRates.convert(max.amount, max.currency.name, siteCurrency.name)
            enc("search[filter_float_price:to]", (cents / 100).toString())
        }

        query.carCriteria.minPowerKw?.let { kw ->
            // otomoto engine_power is in KM (metric horsepower): 1 kW = 1.35962 KM
            val km = (kw * 1.35962).toLong()
            enc("search[filter_float_engine_power:from]", km.toString())
        }

        when (query.carCriteria.transmission) {
            Transmission.AUTOMATIC -> enc("search[filter_enum_gearbox][0]", "automatic")
            Transmission.MANUAL -> enc("search[filter_enum_gearbox][0]", "manual")
            null -> {}
        }
    }

    internal fun parse(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val nextDataScript = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val now = Clock.System.now()

        return try {
            val root = json.parseToJsonElement(nextDataScript).jsonObject
            val urqlState = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("urqlState")?.jsonObject
                ?: return emptyList()

            // The urql cache is keyed by query hash; find whichever entry decodes to an
            // advertSearch result rather than assuming a fixed key. Each entry decodes
            // inside runCatching so one malformed entry cannot hide the advertSearch one.
            val edges = urqlState.values.firstNotNullOfOrNull { entry ->
                runCatching {
                    entry.jsonObject["data"]?.jsonPrimitive?.contentOrNull?.let { dataText ->
                        json.parseToJsonElement(dataText).jsonObject
                            .get("advertSearch")?.jsonObject
                            ?.get("edges")?.jsonArray
                    }
                }.getOrNull()
            } ?: return emptyList()

            // A single malformed edge is dropped rather than failing the whole page.
            edges.mapNotNull { edge -> runCatching { parseNode(edge, now) }.getOrNull() }
                .distinctBy { it.externalId }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseNode(edge: JsonElement, scrapedAt: kotlinx.datetime.Instant): Listing? {
        val node = edge.jsonObject["node"]?.jsonObject ?: return null
        val externalId = node["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = node["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val url = node["url"]?.jsonPrimitive?.contentOrNull ?: return null

        // The price arrives as a whole-PLN integer ("units") plus a "nanos" fraction;
        // both fold into grosze (Money minor units). Non-positive units mark a listing
        // without a usable price.
        val amount = node["price"]?.jsonObject?.get("amount")?.jsonObject ?: return null
        val units = amount["units"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
        val nanos = amount["nanos"]?.jsonPrimitive?.longOrNull ?: 0L
        // The node states its own currency (Otomoto quotes PLN, Autovit quotes EUR); fall back to
        // the site default when absent or unrecognised.
        val currency = amount["currencyCode"]?.jsonPrimitive?.contentOrNull
            ?.let { code -> Currency.entries.firstOrNull { it.name == code } } ?: siteCurrency
        val price = Money(units * 100 + nanos / 10_000_000, currency)

        val imageUrl = node["thumbnail"]?.jsonObject?.let { thumb ->
            thumb["x2"]?.jsonPrimitive?.contentOrNull ?: thumb["x1"]?.jsonPrimitive?.contentOrNull
        }

        val city = node["location"]?.jsonObject
            ?.get("city")?.jsonObject
            ?.get("name")?.jsonPrimitive?.contentOrNull
        val location = Location(city = city, country = countryCode)

        // Each parameter carries a localized displayValue ("Manualna", "150 000 km") used
        // for the description text and a canonical machine value ("manual", "150000")
        // used for structured parsing. Parameters without a key are skipped.
        val displayValues = HashMap<String, String>()
        val machineValues = HashMap<String, String>()
        node["parameters"]?.jsonArray?.forEach { param ->
            val obj = param as? JsonObject ?: return@forEach
            val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            obj["displayValue"]?.jsonPrimitive?.contentOrNull?.let { displayValues[key] = it }
            obj["value"]?.jsonPrimitive?.contentOrNull?.let { machineValues[key] = it }
        }

        val description = buildString {
            displayValues["year"]?.let { append(it) }
            displayValues["mileage"]?.let {
                if (isNotEmpty()) append(" | ")
                append(it)
            }
        }.takeIf { it.isNotBlank() }

        val vehicle = VehicleTextParser.verifiedByPresence(VehicleInfo(
            firstRegYear = machineValues["year"]?.toIntOrNull()?.takeIf { it in 1900..2100 },
            mileageKm = machineValues["mileage"]?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
            // engine_power is metric HP (KM): 1 kW = 1.35962 KM, so kW = KM / 1.35962.
            powerKw = machineValues["engine_power"]?.toIntOrNull()
                ?.let { (it / 1.35962).toInt() }
                ?.takeIf { it in 20..1000 },
            displacementCc = machineValues["engine_capacity"]?.toIntOrNull()?.takeIf { it in 600..8000 },
            fuel = Fuel.parse(machineValues["fuel_type"]),
            gearbox = when (machineValues["gearbox"]) {
                "automatic" -> Transmission.AUTOMATIC
                "manual" -> Transmission.MANUAL
                else -> null
            },
            bodyType = BodyType.parse(machineValues["body_type"]),
        ))

        return Listing(
            id = "${platformId.name}:$externalId",
            platformId = platformId,
            externalId = externalId,
            url = url,
            title = title,
            price = price,
            imageUrls = listOfNotNull(imageUrl),
            location = location,
            description = description,
            scrapedAt = scrapedAt,
            vehicle = vehicle,
        )
    }
}
