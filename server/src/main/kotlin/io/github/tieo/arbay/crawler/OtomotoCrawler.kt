package io.github.tieo.arbay.crawler

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
class OtomotoCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.OTOMOTO

    override suspend fun search(query: SearchQuery): List<Listing> {
        val carQuery = CarQueryResolver.resolve(query.positiveText)

        val basePath = buildString {
            append("https://www.otomoto.pl/osobowe")
            if (carQuery != null) {
                append("/").append(carQuery.makeSlug)
                carQuery.modelSlug?.let { append("/").append(it) }
            }
        }

        val filters = filterParams(query)
        val maxPages = query.maxPages ?: CrawlerConfig.current.maxPages
        val seen = LinkedHashMap<String, Listing>()

        for (offset in 0 until maxPages) {
            val page = query.startPage + offset
            // Page 1 uses no page param; subsequent pages append &page=N after the filter params.
            val url = if (page <= 1) {
                if (filters.isEmpty()) basePath else "$basePath?$filters"
            } else {
                if (filters.isEmpty()) "$basePath?page=$page" else "$basePath?$filters&page=$page"
            }
            val html = fetchWithFallback(client, url, "OTOMoto")
            val listings = parse(html)

            if (listings.isEmpty()) break
            val newIds = listings.count { it.externalId !in seen }
            listings.forEach { seen.putIfAbsent(it.externalId, it) }
            if (newIds == 0) break
            if (seen.size >= CrawlerConfig.current.maxResultsPerPlatform) break
        }

        return seen.values.toList()
    }

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

        query.firstRegFromYear?.let { enc("search[filter_float_year:from]", it.toString()) }
        query.firstRegToYear?.let { enc("search[filter_float_year:to]", it.toString()) }
        query.maxMileageKm?.let { enc("search[filter_float_mileage:to]", it.toString()) }

        query.minPrice?.let { min ->
            val plnCents = if (min.currency == Currency.PLN) min.amount
            else ExchangeRates.convert(min.amount, min.currency.name, "PLN")
            enc("search[filter_float_price:from]", (plnCents / 100).toString())
        }
        query.maxPrice?.let { max ->
            val plnCents = if (max.currency == Currency.PLN) max.amount
            else ExchangeRates.convert(max.amount, max.currency.name, "PLN")
            enc("search[filter_float_price:to]", (plnCents / 100).toString())
        }

        query.minPowerKw?.let { kw ->
            // otomoto engine_power is in KM (metric horsepower): 1 kW = 1.35962 KM
            val km = (kw * 1.35962).toLong()
            enc("search[filter_float_engine_power:from]", km.toString())
        }

        when (query.transmission) {
            Transmission.AUTOMATIC -> enc("search[filter_enum_gearbox][0]", "automatic")
            Transmission.MANUAL -> enc("search[filter_enum_gearbox][0]", "manual")
            null -> {}
        }
    }

    internal fun parse(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val nextDataScript = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val now = Clock.System.now()
        val json = Json { ignoreUnknownKeys = true }

        return try {
            val root = json.parseToJsonElement(nextDataScript).jsonObject
            val urqlState = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("urqlState")?.jsonObject
                ?: return emptyList()

            // The urql cache is keyed by query hash; find whichever entry decodes to an
            // advertSearch result rather than assuming a fixed key.
            val edges = urqlState.values.firstNotNullOfOrNull { entry ->
                val dataText = entry.jsonObject["data"]?.jsonPrimitive?.contentOrNull
                    ?: return@firstNotNullOfOrNull null
                val inner = json.parseToJsonElement(dataText).jsonObject
                inner["advertSearch"]?.jsonObject?.get("edges")?.jsonArray
            } ?: return emptyList()

            edges.mapNotNull { edge -> parseNode(edge, now) }.distinctBy { it.externalId }
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

        // Prices arrive as a whole-PLN integer ("units") plus a "nanos" fraction; otomoto
        // has never observed to populate nanos, but fold it in for correctness.
        val amount = node["price"]?.jsonObject?.get("amount")?.jsonObject ?: return null
        val units = amount["units"]?.jsonPrimitive?.longOrNull ?: return null
        val nanos = amount["nanos"]?.jsonPrimitive?.longOrNull ?: 0L
        val price = Money(units * 100 + nanos / 10_000_000, Currency.PLN)

        val imageUrl = node["thumbnail"]?.jsonObject?.let { thumb ->
            thumb["x2"]?.jsonPrimitive?.contentOrNull ?: thumb["x1"]?.jsonPrimitive?.contentOrNull
        }

        val city = node["location"]?.jsonObject
            ?.get("city")?.jsonObject
            ?.get("name")?.jsonPrimitive?.contentOrNull
        val location = Location(city = city, country = "PL")

        val parameters = node["parameters"]?.jsonArray?.associate { param ->
            val obj = param.jsonObject
            val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: ""
            val value = obj["displayValue"]?.jsonPrimitive?.contentOrNull ?: ""
            key to value
        } ?: emptyMap()

        val description = buildString {
            parameters["year"]?.let { append(it) }
            parameters["mileage"]?.let {
                if (isNotEmpty()) append(" | ")
                append(it)
            }
        }.takeIf { it.isNotBlank() }

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
        )
    }
}
