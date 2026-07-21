package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import kotlin.math.roundToInt
import org.jsoup.Jsoup

/**
 * Crawler for the OLX classifieds sites (olx.pt and its country siblings). The car category is
 * searched free-text; a whole-page state blob under `window.__PRERENDERED_STATE__` carries the
 * result list at `data.listing.listing.ads`, each ad with a `params` array (year, mileage, fuel,
 * gearbox, ...) that is the site's structured data, so those values are recorded as verified.
 *
 * OLX applies the keyword loosely, so a "volkswagen crafter" query returns other Volkswagen
 * models too. When the query resolves to a specific model, results are narrowed to it by the
 * ad's own `modelo` param (falling back to the title), which is exact rather than a guess.
 */
class OlxCrawler(
    private val client: HttpClient,
    override val platformId: PlatformId = PlatformId.OLX_PT,
    private val host: String = "https://www.olx.pt",
    private val carsPath: String = "carros-motos-e-barcos/carros",
    private val currency: Currency = Currency.EUR,
    private val countryCode: String = "PT",
    private val siteLabel: String = "OLX PT",
) : Crawler {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        val keyword = if (car != null) {
            listOfNotNull(car.makeSlug.replace("-", " "), car.modelSlug).joinToString(" ")
        } else {
            query.positiveText
        }

        return paginate(query) { page ->
            val base = "$host/$carsPath/q-${keyword.encodeUrl()}/"
            val url = if (page <= 1) base else "$base?page=$page"
            parse(fetchWithFallback(client, url, siteLabel), car?.modelSlug)
        }
    }

    /** @param modelSlug when set, keep only ads whose model matches, since the keyword is loose. */
    internal fun parse(html: String, modelSlug: String? = null): List<Listing> {
        val ads = extractAds(html) ?: return emptyList()
        val now = Clock.System.now()
        val model = modelSlug?.replace("-", " ")?.lowercase()

        return ads.filter { model == null || adMatchesModel(it, model) }
            .mapNotNull { runCatching { parseAd(it, now) }.getOrNull() }
            .distinctBy { it.externalId }
    }

    /** OLX applies the keyword loosely, so narrow to the model using the ad's own `modelo` param
     *  (exact) and the title (for ads that omit the param). */
    private fun adMatchesModel(element: JsonElement, model: String): Boolean {
        val obj = element as? JsonObject ?: return false
        val modelo = obj["params"]?.jsonArray?.firstOrNull {
            (it as? JsonObject)?.get("key")?.jsonPrimitive?.contentOrNull == "modelo"
        }?.jsonObject?.let { it["normalizedValue"]?.jsonPrimitive?.contentOrNull ?: it["value"]?.jsonPrimitive?.contentOrNull }
            ?.lowercase()
        if (modelo != null) return modelo.contains(model) || model.contains(modelo)
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return false
        return title.contains(model)
    }

    /** Pull the ads array out of the `window.__PRERENDERED_STATE__` string, which is a JSON string
     *  holding JSON: decode the string once, then read `data.listing.listing.ads`. */
    private fun extractAds(html: String): JsonArray? {
        val script = Jsoup.parse(html).select("script").firstOrNull {
            it.data().contains("__PRERENDERED_STATE__")
        }?.data() ?: return null

        val encoded = STATE_REGEX.find(script)?.groupValues?.get(1) ?: return null
        return try {
            val decoded = json.parseToJsonElement("\"$encoded\"").jsonPrimitive.content
            json.parseToJsonElement(decoded).jsonObject["listing"]?.jsonObject
                ?.get("listing")?.jsonObject
                ?.get("ads")?.jsonArray
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAd(element: JsonElement, scrapedAt: kotlinx.datetime.Instant): Listing? {
        val obj = element.jsonObject
        val externalId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return null

        val amount = obj["price"]?.jsonObject?.get("regularPrice")?.jsonObject
            ?.get("value")?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: return null
        val price = Money(amount * 100, currency)

        val params = HashMap<String, String>()
        obj["params"]?.jsonArray?.forEach { p ->
            val po = p as? JsonObject ?: return@forEach
            val key = po["key"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val value = po["normalizedValue"]?.jsonPrimitive?.contentOrNull
                ?: po["value"]?.jsonPrimitive?.contentOrNull
            if (value != null) params[key] = value
        }

        val imageUrl = obj["photos"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: obj["photos"]?.jsonArray?.firstOrNull()?.jsonObject?.get("link")?.jsonPrimitive?.contentOrNull

        val cityName = obj["location"]?.jsonObject?.get("cityName")?.jsonPrimitive?.contentOrNull
        val location = Location(city = cityName, country = countryCode)

        val structured = VehicleInfo(
            firstRegYear = params["year"]?.toIntOrNull()?.takeIf { it in 1900..2100 },
            mileageKm = params["quilometros"]?.filter { it.isDigit() }?.toIntOrNull()
                ?.takeIf { it in 1..2_000_000 },
            displacementCc = params["engine_capacity"]?.filter { it.isDigit() }?.toIntOrNull()
                ?.takeIf { it in 600..8000 },
            // OLX's engine_power is metric hp (PS); convert to kW so the power filter enforces.
            powerKw = params["engine_power"]?.filter { it.isDigit() }?.toIntOrNull()
                ?.let { (it * 0.7355).roundToInt() }?.takeIf { it in 10..1500 },
            fuel = Fuel.parse(params["combustivel"]),
            gearbox = when (params["gearbox"]) {
                "automatic", "automatica", "automatico" -> Transmission.AUTOMATIC
                "manual" -> Transmission.MANUAL
                else -> null
            },
            doors = params["portas"]?.substringBefore("-")?.toIntOrNull()?.takeIf { it in 1..7 },
            condition = when (params["condicao"]) {
                "usado" -> VehicleCondition.USED
                "novo" -> VehicleCondition.NEW
                else -> null
            },
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
            scrapedAt = scrapedAt,
            vehicle = VehicleTextParser.verifiedByPresence(structured),
        )
    }

    private companion object {
        val STATE_REGEX = Regex("""__PRERENDERED_STATE__\s*=\s*"(.*?)";""", RegexOption.DOT_MATCHES_ALL)
    }
}
