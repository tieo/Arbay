package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*

/**
 * Crawler for ricardo.ch, Switzerland's largest general marketplace and the only Swiss source
 * that exposes vehicle listings on a fetchable, filtering search URL.
 *
 * The page is a Next.js App Router app: results are server-rendered into the React Server
 * Component stream as `self.__next_f.push([1,"<json-string>"])` chunks rather than a single
 * `__NEXT_DATA__` blob. Concatenating the decoded chunks yields a payload holding an
 * `"articles"` array with id, title, price, image, condition and shipping location.
 *
 * autoscout24.ch is deliberately not used: none of its search URLs apply the make/model filter
 * (slug, numeric id and query-param forms all return the same unfiltered promo feed) and it
 * issues no client-side listings request, so no fetchable URL yields filtered Swiss results.
 */
class RicardoCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.RICARDO

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        // A car query is searched by make and model words; ricardo has no vehicle-specific
        // category filter in the URL, so the text carries the intent.
        val car = CarQueryResolver.resolve(query.positiveText)
        val text = if (car != null) {
            listOfNotNull(car.makeSlug.replace("-", " "), car.modelSlug).joinToString(" ")
        } else {
            query.positiveText
        }

        return paginate(query) { page ->
            val base = "https://www.ricardo.ch/de/s/${text.encodeUrl()}/"
            val url = if (page <= 1) base else "$base?page=$page"
            parse(fetchWithFallback(client, url, "ricardo.ch"))
        }
    }

    /** Decode the RSC stream and map its `articles` array to listings. */
    internal fun parse(html: String): List<Listing> {
        val articles = extractArticles(html) ?: return emptyList()
        val now = Clock.System.now()

        return articles.mapNotNull { element ->
            runCatching { parseArticle(element, now) }.getOrNull()
        }
    }

    /**
     * Rebuild the RSC payload from the `self.__next_f.push([1,"…"])` chunks and pull out the
     * `articles` array. Each chunk body is a JSON-encoded string, so it is decoded by parsing it
     * as one, which handles every escape the stream uses.
     */
    private fun extractArticles(html: String): JsonArray? {
        val blob = buildString {
            CHUNK_REGEX.findAll(html).forEach { match ->
                val decoded = runCatching {
                    json.parseToJsonElement("\"${match.groupValues[1]}\"").jsonPrimitive.content
                }.getOrNull()
                if (decoded != null) append(decoded)
            }
        }

        val start = blob.indexOf(ARTICLES_KEY)
        if (start < 0) return null
        val arrayStart = start + ARTICLES_KEY.length

        // The payload is not valid JSON as a whole, so take the balanced array by hand.
        var depth = 0
        for (i in arrayStart until blob.length) {
            when (blob[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching {
                            json.parseToJsonElement(blob.substring(arrayStart, i + 1)).jsonArray
                        }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun parseArticle(element: JsonElement, scrapedAt: kotlinx.datetime.Instant): Listing? {
        val obj = element as? JsonObject ?: return null
        val externalId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        // A general marketplace answers a car query with as many parts as vehicles (a Crafter
        // search returns floor mats, a licence-plate light, a keychain). ricardo states the kind
        // in productTypeKey, so drop anything it classifies as something other than a vehicle.
        // A missing key is kept: unknown must not silently discard a real listing.
        val productType = obj["productTypeKey"]?.jsonPrimitive?.contentOrNull
        if (productType != null && productType !in VEHICLE_PRODUCT_TYPES) return null

        // Prices are whole Swiss francs. A buy-now price wins; otherwise the current bid stands in,
        // which is what a buyer would pay right now on an auction listing.
        val francs = obj["buyNowPrice"]?.jsonPrimitive?.longOrNull
            ?: obj["bidPrice"]?.jsonPrimitive?.longOrNull
            ?: return null
        if (francs <= 0) return null
        val price = Money(francs * 100, Currency.CHF)

        val imageUrl = obj["image"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }

        // Shipping carries the seller's town, the only location the search payload exposes.
        val ship = obj["shipping"]?.jsonArray?.firstOrNull()?.jsonObject
        val location = Location(
            city = ship?.get("city")?.jsonPrimitive?.contentOrNull,
            zip = ship?.get("zipCode")?.jsonPrimitive?.contentOrNull,
            country = "CH",
        )

        val condition = VehicleCondition.parse(obj["conditionKey"]?.jsonPrimitive?.contentOrNull)
        val vehicle = condition?.let {
            VehicleTextParser.verifiedByPresence(VehicleInfo(condition = it))
        }

        return Listing(
            id = "${platformId.name}:$externalId",
            platformId = platformId,
            externalId = externalId,
            url = "https://www.ricardo.ch/de/a/$externalId/",
            title = title,
            price = price,
            imageUrls = listOfNotNull(imageUrl),
            location = location,
            scrapedAt = scrapedAt,
            vehicle = vehicle,
        )
    }

    private companion object {
        val CHUNK_REGEX = Regex("""self\.__next_f\.push\(\[1,"(.*?)"]\)""", RegexOption.DOT_MATCHES_ALL)
        const val ARTICLES_KEY = "\"articles\":"

        /** ricardo product types that are a whole vehicle. Everything else it classifies
         *  (auto_part, car_mat, wheel, rim, trailer_coupling, keychain, ...) is an accessory. */
        val VEHICLE_PRODUCT_TYPES = setOf(
            "car", "commercial_vehicle", "truck", "caravan", "motorhome", "camper",
            "van", "bus", "oldtimer",
        )
    }
}
