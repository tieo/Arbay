package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.model.carCriteria
import io.ktor.client.*
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*

/**
 * Crawler for sauto.cz (Seznam), the largest Czech used-vehicle marketplace. Listings come
 * straight from the site's own JSON API (/api/v1/items/search), the same endpoint its React
 * frontend calls, so no HTML scraping is needed and no bot protection stands in the way.
 */
class SautoCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.SAUTO

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText)
        val maxPages = query.maxPages ?: CrawlerConfig.current.maxPages
        val seen = LinkedHashMap<String, Listing>()
        val categoryId = resolveCategory(car)

        for (offset in 0 until maxPages) {
            val page = query.startPage + offset
            val skip = (page - 1) * PAGE_SIZE
            val url = buildUrl(car, categoryId, skip, query)
            val json = fetchWithFallback(client, url, "Sauto")
            val listings = parse(json)

            if (listings.isEmpty()) break
            val newIds = listings.count { it.externalId !in seen }
            listings.forEach { seen.putIfAbsent(it.externalId, it) }
            if (newIds == 0) break
            if (seen.size >= CrawlerConfig.current.maxResultsPerPlatform) break
        }

        return seen.values.toList()
    }

    /**
     * Probes each vehicle category for a single page and returns the first whose results
     * actually carry the requested model. The API silently drops the model filter (and
     * returns the whole make instead) whenever the model doesn't exist in the probed
     * category: "Crafter" isn't a passenger car (838), it lives under light
     * commercial (839). Make-only queries skip probing and use the passenger category,
     * the largest and most common one.
     */
    private suspend fun resolveCategory(car: CarQueryResolver.CarQuery?): Int {
        val modelSlug = car?.modelSlug ?: return CATEGORIES.first()
        for (categoryId in CATEGORIES) {
            val json = try {
                fetchWithFallback(client, buildUrl(car, categoryId, 0), "Sauto")
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                continue
            }
            val matches = parse(json).any { it.title.contains(modelSlug, ignoreCase = true) }
            if (matches) return categoryId
        }
        return CATEGORIES.first()
    }

    private fun buildUrl(car: CarQueryResolver.CarQuery?, categoryId: Int, offset: Int, query: SearchQuery? = null): String {
        val modelParam = if (car != null) {
            val slug = if (car.modelSlug != null) "${car.makeSlug}:${car.modelSlug}" else car.makeSlug
            "&manufacturer_model_seo=${slug.encodeUrl()}"
        } else {
            ""
        }
        return "https://www.sauto.cz/api/v1/items/search?limit=$PAGE_SIZE&offset=$offset$modelParam" +
            "&category_id=$categoryId&operating_lease=false${query?.let { filterParams(it) } ?: ""}"
    }

    /**
     * Appends supported sauto.cz filter parameters. Parameter names verified live against the
     * /api/v1/items/search endpoint (any unrecognised name appears in response.warnings.unsupported_filters).
     *
     * Mapping:
     *   firstRegFromYear  -> vehicle_age_from  (integer year, filters by in_operation_date)
     *   firstRegToYear    -> vehicle_age_to    (integer year)
     *   maxMileageKm      -> tachometer_to     (km, integer)
     *   minPowerKw        -> engine_power_from (kW, integer)
     *   maxPrice (EUR)    -> price_to          (CZK whole units, converted via ExchangeRates)
     *   minPrice (EUR)    -> price_from        (CZK whole units, converted via ExchangeRates)
     *   AUTOMATIC         -> gearbox_seo=automaticka
     *   MANUAL            -> gearbox_seo=manualni
     */
    /** This site's own fuel codes. */
    private fun siteFuel(fuel: Fuel): Int? = when (fuel) {
        Fuel.PETROL -> 1
        Fuel.DIESEL -> 2
        Fuel.LPG -> 3
        Fuel.ELECTRIC -> 4
        Fuel.HYBRID_PETROL, Fuel.HYBRID_DIESEL, Fuel.PLUGIN_HYBRID, Fuel.MILD_HYBRID -> 5
        Fuel.CNG -> 6
        else -> null
    }

    private fun filterParams(query: SearchQuery): String = buildString {
        query.carCriteria.firstRegFromYear?.let { append("&vehicle_age_from=$it") }
        query.carCriteria.firstRegToYear?.let { append("&vehicle_age_to=$it") }
        query.carCriteria.minMileageKm?.let { append("&tachometer_from=$it") }
        query.carCriteria.maxMileageKm?.let { append("&tachometer_to=$it") }
        // This site's own fuel codebook, read back from its API's own echo of the value it stored:
        // 1 Benzín, 2 Nafta, 3 LPG+benzín, 4 Elektro, 5 Hybridní, 6 CNG+benzín. Every car but 21 of
        // 105572 states one, so it is asked for at the source.
        query.carCriteria.fuels.singleOrNull()?.let { fuel -> siteFuel(fuel)?.let { append("&fuel_cb=$it") } }
        query.carCriteria.minPowerKw?.let { append("&engine_power_from=$it") }
        query.maxPrice?.let { max ->
            val czk = if (max.currency == Currency.CZK) max.amount / 100
            else ExchangeRates.convert(max.amount, max.currency.name, "CZK") / 100
            append("&price_to=$czk")
        }
        query.minPrice?.let { min ->
            val czk = if (min.currency == Currency.CZK) min.amount / 100
            else ExchangeRates.convert(min.amount, min.currency.name, "CZK") / 100
            append("&price_from=$czk")
        }
        when (query.carCriteria.transmission) {
            Transmission.AUTOMATIC -> append("&gearbox_seo=automaticka")
            Transmission.MANUAL -> append("&gearbox_seo=manualni")
            null -> {}
        }
    }

    internal fun parse(json: String): List<Listing> {
        val now = Clock.System.now()
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }
        val results = root["results"]?.jsonArray ?: return emptyList()

        // A single malformed result is dropped rather than failing the whole page.
        return results.mapNotNull { element -> runCatching { parseItem(element, now) }.getOrNull() }
            .distinctBy { it.externalId }
    }

    private fun parseItem(element: JsonElement, scrapedAt: kotlinx.datetime.Instant): Listing? {
        val obj = element as? JsonObject ?: return null
        val externalId = obj["id"]?.jsonPrimitive?.longOrNull?.toString() ?: return null
        val title = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        if (obj["price_by_agreement"]?.jsonPrimitive?.booleanOrNull == true) return null
        val rawPrice = obj["price"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
        // The API returns whole CZK, Money stores minor units (haléře), so scale by 100.
        val price = Money(rawPrice * 100, Currency.CZK)

        val categorySeo = obj["category"]?.jsonObject?.get("seo_name")?.jsonPrimitive?.contentOrNull
        val manufacturerSeo = obj["manufacturer_cb"]?.jsonObject?.get("seo_name")?.jsonPrimitive?.contentOrNull
        val modelSeo = obj["model_cb"]?.jsonObject?.get("seo_name")?.jsonPrimitive?.contentOrNull
        val url = if (categorySeo != null && manufacturerSeo != null && modelSeo != null) {
            "https://www.sauto.cz/$categorySeo/detail/$manufacturerSeo/$modelSeo/$externalId"
        } else {
            "https://www.sauto.cz/detail/$externalId"
        }

        val imageUrl = obj["images"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            ?.let { if (it.startsWith("//")) "https:$it" else it }

        val locality = obj["locality"]?.jsonObject
        val city = locality?.get("municipality")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: locality?.get("district")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val location = Location(city = city, country = "CZ")

        // First registration comes from in_operation_date; manufacturing_date is the fallback.
        // The date is ISO-formatted (yyyy-MM-dd), so year and month come from fixed offsets.
        val regDate = (obj["in_operation_date"] ?: obj["manufacturing_date"])?.jsonPrimitive?.contentOrNull
        val year = regDate?.take(4)?.toIntOrNull()?.takeIf { it in 1900..2100 }
        val km = obj["tachometer"]?.jsonPrimitive?.longOrNull?.takeIf { it in 1..2_000_000 }
        val description = buildString {
            year?.let { append("EZ: $it") }
            km?.let {
                if (isNotEmpty()) append(" | ")
                append("$it km")
            }
        }.takeIf { it.isNotBlank() }

        val vehicle = VehicleTextParser.verifiedByPresence(VehicleInfo(
            firstRegYear = year,
            firstRegMonth = regDate?.takeIf { it.length >= 7 }
                ?.substring(5, 7)?.toIntOrNull()?.takeIf { it in 1..12 },
            mileageKm = km?.toInt(),
            fuel = Fuel.parse(obj["fuel_cb"]?.jsonObject?.get("seo_name")?.jsonPrimitive?.contentOrNull),
            gearbox = when (obj["gearbox_cb"]?.jsonObject?.get("seo_name")?.jsonPrimitive?.contentOrNull) {
                "automaticka" -> Transmission.AUTOMATIC
                "manualni" -> Transmission.MANUAL
                else -> null
            },
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

    companion object {
        private const val PAGE_SIZE = 20

        // Vehicle category ids from /api/v1/categories/search: 838 = Osobní (passenger cars,
        // the largest category and default for most makes), 839 = Užitková (vans/light
        // commercial, e.g. VW Crafter), 840 = Nákladní (trucks).
        private val CATEGORIES = listOf(838, 839, 840)
    }
}
