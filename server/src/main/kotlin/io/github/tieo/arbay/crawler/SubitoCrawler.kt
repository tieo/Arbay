package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/**
 * Crawler for subito.it, Italy's largest classifieds marketplace, covering the private sellers
 * AutoScout24's Italian inventory largely misses.
 *
 * Listings are server-rendered into `__NEXT_DATA__` at
 * `props.pageProps.initialState.items.originalList`. Each ad carries a `features` map keyed by
 * uri ("/price", "/mileage_scalar", "/year", ...), which is the site's own structured data, so
 * the values it yields are recorded as verified and may exclude on a filter.
 */
class SubitoCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.SUBITO

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        val text = if (car != null) {
            listOfNotNull(car.makeSlug.replace("-", " "), car.modelSlug).joinToString(" ")
        } else {
            query.positiveText
        }

        return paginate(query) { page ->
            // The vendita/auto category scopes the search to whole vehicles; `o` is the page.
            val base = "https://www.subito.it/annunci-italia/vendita/auto/?q=${text.encodeUrl()}"
            val url = if (page <= 1) base else "$base&o=$page"
            parse(fetchWithFallback(client, url, "Subito"))
        }
    }

    internal fun parse(html: String): List<Listing> {
        val script = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val now = Clock.System.now()

        return try {
            val items = json.parseToJsonElement(script).jsonObject["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("initialState")?.jsonObject
                ?.get("items")?.jsonObject
                ?.get("originalList")?.jsonArray
                ?: return emptyList()

            items.mapNotNull { runCatching { parseItem(it, now) }.getOrNull() }
                .distinctBy { it.externalId }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseItem(element: JsonElement, scrapedAt: kotlinx.datetime.Instant): Listing? {
        val obj = element.jsonObject
        val title = obj["subject"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        // The urn reads "id:ad:<uuid>:list:<numeric id>"; the trailing number is the ad id.
        val externalId = obj["urn"]?.jsonPrimitive?.contentOrNull?.substringAfterLast(":")
            ?.takeIf { it.isNotBlank() } ?: return null

        val url = obj["urls"]?.jsonObject?.get("default")?.jsonPrimitive?.contentOrNull ?: return null

        val features = obj["features"]?.jsonObject ?: JsonObject(emptyMap())
        fun featureKey(uri: String): String? = features[uri]?.jsonObject
            ?.get("values")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("key")?.jsonPrimitive?.contentOrNull
        fun featureValue(uri: String): String? = features[uri]?.jsonObject
            ?.get("values")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("value")?.jsonPrimitive?.contentOrNull

        // Price is a plain euro amount in the feature key.
        val euros = featureKey("/price")?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val price = Money(euros * 100, Currency.EUR)

        val imageUrl = obj["images"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("cdnBaseUrl")?.jsonPrimitive?.contentOrNull

        // geo.city is the province and geo.town the municipality where present.
        val geo = obj["geo"]?.jsonObject
        fun geoValue(part: String): String? = geo?.get(part)?.jsonObject
            ?.get("value")?.jsonPrimitive?.contentOrNull
        val location = Location(
            city = geoValue("town") ?: geoValue("city"),
            country = "IT",
        )

        // "103/140" is kW/CV, so the kilowatt figure is the part before the slash.
        val powerKw = featureKey("/power")?.substringBefore("/")?.toIntOrNull()?.takeIf { it in 20..1000 }
        val emissionEuro = featureValue("/pollution")
            ?.let { Regex("""\d""").find(it)?.value?.toIntOrNull() }

        val structured = VehicleInfo(
            firstRegYear = featureKey("/year")?.toIntOrNull()?.takeIf { it in 1900..2100 },
            firstRegMonth = featureKey("/month")?.toIntOrNull()?.takeIf { it in 1..12 },
            mileageKm = featureKey("/mileage_scalar")?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
            powerKw = powerKw,
            fuel = Fuel.parse(featureValue("/fuel")),
            gearbox = Transmission.parse(featureValue("/gearbox")),
            seats = featureValue("/seats")?.toIntOrNull()?.takeIf { it in 1..20 },
            condition = italianCondition(featureValue("/vehicle_status")),
            color = featureValue("/color"),
            emissionClassEuro = emissionEuro,
        )

        return Listing(
            id = "${platformId.name}:$externalId",
            platformId = platformId,
            externalId = externalId,
            url = url,
            title = title,
            price = price,
            imageUrls = listOfNotNull(imageUrl),
            location = location,
            description = obj["body"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
            scrapedAt = scrapedAt,
            vehicle = VehicleTextParser.verifiedByPresence(structured),
        )
    }

    /** subito states the condition in Italian, which the shared parser does not cover. */
    private fun italianCondition(raw: String?): VehicleCondition? = when {
        raw == null -> null
        raw.contains("usato", ignoreCase = true) -> VehicleCondition.USED
        raw.contains("nuovo", ignoreCase = true) -> VehicleCondition.NEW
        raw.contains("km 0", ignoreCase = true) -> VehicleCondition.PRE_REGISTRATION
        raw.contains("incidentat", ignoreCase = true) -> VehicleCondition.DAMAGED
        else -> null
    }
}
