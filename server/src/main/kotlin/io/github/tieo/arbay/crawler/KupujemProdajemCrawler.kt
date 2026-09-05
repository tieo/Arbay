package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/**
 * Crawler for kupujemprodajem.com, Serbia's largest classifieds marketplace. Used because
 * polovniautomobili.com ignores the model filter and hides its results behind a client-only API.
 *
 * The car category is searched free-text; results are server-rendered into `__NEXT_DATA__` at
 * `props.initialReduxState.search.lastSearchResult.ads`. Each ad carries typed car fields
 * (car_model_name, car_fuel_type_name, car_gearbox, condition, currency, price) plus an
 * `ad_attributes` list holding the manufacture year and odometer. These are the site's own data,
 * so they are recorded as verified. Prices are quoted in EUR or RSD per the ad's currency field.
 */
class KupujemProdajemCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.KUPUJEM

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText)
        val keyword = if (car != null) {
            listOfNotNull(car.makeSlug.replace("-", " "), car.modelSlug).joinToString(" ")
        } else {
            query.positiveText
        }

        return paginate(query) { page ->
            // kupujem's general search takes no car-spec URL filters (year/km/gearbox live on the
            // card, price too), so those are enforced by the post-filter, which is exact here.
            val base = "https://www.kupujemprodajem.com/automobili/pretraga?keywords=${keyword.encodeUrl()}"
            val url = if (page <= 1) base else "$base&page=$page"
            parse(fetchWithFallback(client, url, "KupujemProdajem"), car?.modelSlug)
        }
    }

    /** @param modelSlug when set, keep only ads whose car_model_name matches, since the keyword is loose. */
    internal fun parse(html: String, modelSlug: String? = null): List<Listing> {
        val script = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val now = Clock.System.now()
        val model = modelSlug?.replace("-", " ")?.lowercase()

        return try {
            val ads = json.parseToJsonElement(script).jsonObject["props"]?.jsonObject
                ?.get("initialReduxState")?.jsonObject
                ?.get("search")?.jsonObject
                ?.get("lastSearchResult")?.jsonObject
                ?.get("ads")?.jsonArray
                ?: return emptyList()

            ads.mapNotNull { runCatching { parseAd(it, now, model) }.getOrNull() }
                .distinctBy { it.externalId }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseAd(element: JsonElement, scrapedAt: kotlinx.datetime.Instant, model: String?): Listing? {
        val obj = element.jsonObject
        // Only real car ads carry is_car; skip the help/FAQ entries that share the ads channel.
        if (obj["is_car"]?.jsonPrimitive?.booleanOrNull != true) return null

        val externalId = obj["ad_id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: return null

        val modelName = obj["car_model_name"]?.jsonPrimitive?.contentOrNull?.lowercase()
        if (model != null && modelName != null && !modelName.contains(model) && !model.contains(modelName)) return null

        val amount = obj["price"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
        val currency = when (obj["currency"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "eur" -> Currency.EUR
            "rsd" -> Currency.RSD
            else -> Currency.EUR
        }
        val price = Money(amount * 100, currency)

        val relUrl = obj["ad_url"]?.jsonPrimitive?.contentOrNull ?: return null
        val url = "https://www.kupujemprodajem.com$relUrl"

        // Year and odometer live in the carSummaryLong attribute list keyed by code.
        val attrs = HashMap<String, String>()
        obj["ad_attributes"]?.jsonArray?.forEach { section ->
            (section as? JsonObject)?.get("attributes")?.jsonArray?.forEach { attr ->
                val ao = attr as? JsonObject ?: return@forEach
                val code = ao["code"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val value = ao["values"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
                if (value != null) attrs[code] = value
            }
        }

        val structured = VehicleInfo(
            firstRegYear = attrs["carManufactureYear"]?.filter { it.isDigit() }?.toIntOrNull()
                ?.takeIf { it in 1900..2100 },
            mileageKm = attrs["carKm"]?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
            fuel = Fuel.parse(obj["car_fuel_type_name"]?.jsonPrimitive?.contentOrNull ?: attrs["carFuelType"]),
            gearbox = when (obj["car_gearbox"]?.jsonPrimitive?.contentOrNull?.substringBefore(":")) {
                "automatic" -> Transmission.AUTOMATIC
                "manual" -> Transmission.MANUAL
                else -> null
            },
            doors = obj["car_doors"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..7 },
            bodyType = BodyType.parse(obj["car_body_type_name"]?.jsonPrimitive?.contentOrNull),
            condition = when (obj["condition"]?.jsonPrimitive?.contentOrNull) {
                "used" -> VehicleCondition.USED
                "new" -> VehicleCondition.NEW
                else -> null
            },
        )

        val imageUrl = obj["image_url"]?.jsonPrimitive?.contentOrNull
            ?: obj["images"]?.jsonArray?.firstOrNull()?.let {
                (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull
            }

        return Listing(
            id = "${platformId.name}:$externalId",
            platformId = platformId,
            externalId = externalId,
            url = url,
            title = title,
            price = price,
            imageUrls = listOfNotNull(imageUrl?.takeIf { it.startsWith("http") }),
            location = Location(city = obj["location_name"]?.jsonPrimitive?.contentOrNull, country = "RS"),
            description = obj["description_snippet_decoded"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
            scrapedAt = scrapedAt,
            vehicle = VehicleTextParser.verifiedByPresence(structured),
        )
    }
}
