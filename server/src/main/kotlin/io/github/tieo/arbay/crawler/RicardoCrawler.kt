package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
class RicardoCrawler(private val client: HttpClient) : Crawler, FetchesEveryPage, KnowsLocation {
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

        // The vehicle-type guard only makes sense for a car search; a product search ("Parkett-
        // schleifmaschine") legitimately wants grinding_machine etc., so it must not be filtered out.
        val vehiclesOnly = car != null
        return paginate(query) { page ->
            val base = "https://www.ricardo.ch/de/s/${text.encodeUrl()}/"
            val url = if (page <= 1) base else "$base?page=$page"
            parse(fetchWithFallback(client, url, "ricardo.ch"), vehiclesOnly)
        }
    }

    /** Decode the RSC stream and map its `articles` array to listings. */
    internal fun parse(html: String, vehiclesOnly: Boolean = false): List<Listing> {
        val articles = extractArticles(html) ?: return emptyList()
        val now = Clock.System.now()

        return articles.mapNotNull { element ->
            runCatching { parseArticle(element, now, vehiclesOnly) }.getOrNull()
        }
    }

    /**
     * Rebuild the RSC payload from the `self.__next_f.push([1,"…"])` chunks and pull out the
     * `articles` array. Each chunk body is a JSON-encoded string, so it is decoded by parsing it
     * as one, which handles every escape the stream uses.
     */
    private fun extractArticles(html: String): JsonArray? {
        val blob = decodeChunks(html)

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

    /** Concatenate every `self.__next_f.push([1,"…"])` chunk body, escape-decoded. Scanned by hand,
     *  not by regex: the bodies are ~1 MB and a backtracking string pattern overflows the stack. */
    private fun decodeChunks(html: String): String {
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val start = html.indexOf(CHUNK_MARKER, i)
            if (start < 0) break
            var j = start + CHUNK_MARKER.length
            val body = StringBuilder()
            while (j < html.length) {
                val c = html[j]
                if (c == '\\' && j + 1 < html.length) { body.append(c).append(html[j + 1]); j += 2; continue }
                if (c == '"') break
                body.append(c); j++
            }
            runCatching { json.parseToJsonElement("\"$body\"").jsonPrimitive.content }
                .getOrNull()?.let { sb.append(it) }
            i = j + 1
        }
        return sb.toString()
    }

    private fun parseArticle(element: JsonElement, scrapedAt: kotlinx.datetime.Instant, vehiclesOnly: Boolean): Listing? {
        val obj = element as? JsonObject ?: return null
        val externalId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        // On a car query, a general marketplace answers with as many parts as vehicles (a Crafter
        // search returns floor mats, a licence-plate light, a keychain). ricardo states the kind
        // in productTypeKey, so drop anything it classifies as something other than a vehicle.
        // A missing key is kept: unknown must not silently discard a real listing. Product searches
        // pass through untouched — their productType (grinding_machine, …) is exactly what's wanted.
        if (vehiclesOnly) {
            val productType = obj["productTypeKey"]?.jsonPrimitive?.contentOrNull
            if (productType != null && productType !in VEHICLE_PRODUCT_TYPES) return null
        }

        // Prices are whole Swiss francs. A buy-now price wins, because that is a price someone is
        // asking; without one there is only the bid so far, which is not what the thing will cost.
        // ricardo says which it is, so the listing carries it rather than the reader guessing.
        val hasAuction = obj["hasAuction"]?.jsonPrimitive?.booleanOrNull ?: false
        val hasBuyNow = obj["hasBuyNow"]?.jsonPrimitive?.booleanOrNull ?: false
        val francs = obj["buyNowPrice"]?.jsonPrimitive?.longOrNull
            ?: obj["bidPrice"]?.jsonPrimitive?.longOrNull
            ?: return null
        val saleType = if (hasAuction && !hasBuyNow) SaleType.AUCTION else SaleType.FIXED_PRICE
        val endsAt = obj["endDate"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?.takeIf { saleType == SaleType.AUCTION }
        val bids = obj["bidsCount"]?.jsonPrimitive?.intOrNull
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
            saleType = saleType,
            auctionEndsAt = endsAt,
            bidCount = bids,
        )
    }

    private companion object {
        const val CHUNK_MARKER = "self.__next_f.push([1,\""
        const val ARTICLES_KEY = "\"articles\":"

        /** ricardo product types that are a whole vehicle. Everything else it classifies
         *  (auto_part, car_mat, wheel, rim, trailer_coupling, keychain, ...) is an accessory. */
        val VEHICLE_PRODUCT_TYPES = setOf(
            "car", "commercial_vehicle", "truck", "caravan", "motorhome", "camper",
            "van", "bus", "oldtimer",
        )
    }
}
