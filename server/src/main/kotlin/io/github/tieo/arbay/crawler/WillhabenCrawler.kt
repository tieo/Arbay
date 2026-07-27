package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

class WillhabenCrawler(private val client: HttpClient) : Crawler, FiltersAtTheSource, KnowsListingAge, KnowsLocation {
    override val nativeCriteria = setOf(FiltersAtTheSource.Criterion.YEAR, FiltersAtTheSource.Criterion.MILEAGE, FiltersAtTheSource.Criterion.PRICE, FiltersAtTheSource.Criterion.POWER)

    override val platformId = PlatformId.WILLHABEN

    private val json = Json { ignoreUnknownKeys = true }

    private val carBase = "https://www.willhaben.at/iad/gebrauchtwagen/auto/gebrauchtwagenboerse"

    override suspend fun search(query: SearchQuery): List<Listing> {
        // willhaben's used-car vertical ignores keyword search — it filters by numeric make/model IDs
        // (the `CAR_MODEL/MAKE` and `CAR_MODEL/MODEL` params). So a car query is resolved to those IDs:
        // the make from a static map, the model from the make-filtered page's own filter navigator.
        // The general marktplatz (keyword) handles everything non-car.
        val carQuery = CarQueryResolver.resolve(query.positiveText)
        val makeId = carQuery?.makeSlug?.let { WILLHABEN_MAKE_IDS[it] }
        if (carQuery != null && makeId != null) return searchCars(makeId, carQuery.modelSlug, query)

        val url = "https://www.willhaben.at/iad/kaufen-und-verkaufen/marktplatz?keyword=${query.positiveText.encodeUrl()}"
        // A current Chrome TLS fingerprint (rnet, step 2 of the chain) is served the full
        // __NEXT_DATA__ page; the browser tiers remain as a fallback.
        val html = fetchWithFallback(client, url, "willhaben", primeUrl = "https://www.willhaben.at", extraWaitMs = 1500)
        return parseSearchResults(html)
    }

    private suspend fun searchCars(makeId: Int, modelSlug: String?, query: SearchQuery): List<Listing> {
        val makeHtml = fetchWithFallback(
            client, "$carBase?CAR_MODEL/MAKE=$makeId", "willhaben",
            primeUrl = "https://www.willhaben.at", extraWaitMs = 1500,
        )
        // Resolve the model against this make's filter navigator, then apply the native spec filters
        // so willhaben returns only matching cars.
        val modelId = modelSlug?.let { resolveModelId(makeHtml, it) }
        val url = buildString {
            append("$carBase?CAR_MODEL/MAKE=$makeId")
            modelId?.let { append("&CAR_MODEL/MODEL=$it") }
            append(filterParams(query))
        }
        return parseSearchResults(
            fetchWithFallback(client, url, "willhaben", primeUrl = "https://www.willhaben.at", extraWaitMs = 1500),
        )
    }

    /** willhaben's native car filter params, verified live. ENGINEEFFECT is in PS (metric hp), so a
     *  kW floor is converted. Gearbox is left to the post-filter — willhaben ships it on the card. */
    private fun filterParams(query: SearchQuery): String {
        val f = query.toCarFilters() ?: return ""
        return buildString {
            f.firstRegFromYear?.let { append("&YEAR_MODEL_FROM=$it") }
            f.firstRegToYear?.let { append("&YEAR_MODEL_TO=$it") }
            f.minMileageKm?.let { append("&MILEAGE_FROM=$it") }
            f.maxMileageKm?.let { append("&MILEAGE_TO=$it") }
            f.minPriceEur?.let { append("&PRICE_FROM=$it") }
            f.maxPriceEur?.let { append("&PRICE_TO=$it") }
            f.minPowerKw?.let { append("&ENGINEEFFECT_FROM=${kotlin.math.round(it / 0.7355).toInt()}") }
        }
    }

    /** Find the willhaben CAR_MODEL/MODEL id whose filter label matches [modelSlug], reading the
     *  model navigator embedded in a make-filtered page's __NEXT_DATA__. */
    private fun resolveModelId(makeHtml: String, modelSlug: String): Int? {
        val data = Jsoup.parse(makeHtml).selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val want = modelSlug.lowercase().replace("-", "").replace(" ", "")
        return Regex("\"label\":\"([^\"]{1,40})\"[^}]{0,140}?CAR_MODEL/MODEL\"[^}]{0,60}?\"value\":\"(\\d+)\"")
            .findAll(data)
            .mapNotNull { m -> m.groupValues[2].toIntOrNull()?.let { m.groupValues[1] to it } }
            .firstOrNull { (label, _) ->
                val l = label.lowercase().replace("-", "").replace(" ", "")
                l == want || l.startsWith(want) || want.startsWith(l)
            }?.second
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // Willhaben uses Next.js — all product data is in __NEXT_DATA__
        val scriptEl = doc.selectFirst("script#__NEXT_DATA__")
            ?: throw CrawlerBlockedException("willhaben: page data not found (blocked?)", ErrorType.BLOCKED_403)
        val rootJson = try {
            json.parseToJsonElement(scriptEl.data()).jsonObject
        } catch (_: Exception) {
            return emptyList()
        }

        val pageProps = rootJson["props"]?.jsonObject?.get("pageProps")?.jsonObject
        // Willhaben sometimes returns error objects instead of search results
        val errorMsg = pageProps?.get("error")?.jsonPrimitive?.contentOrNull
            ?: pageProps?.get("errorMessage")?.jsonPrimitive?.contentOrNull
        if (errorMsg != null) {
            throw CrawlerBlockedException("willhaben: $errorMsg", ErrorType.PARSE_ERROR)
        }

        val items = pageProps
            ?.get("searchResult")?.jsonObject
            ?.get("advertSummaryList")?.jsonObject
            ?.get("advertSummary")?.jsonArray ?: return emptyList()

        return items.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.longOrNull?.toString() ?: return@mapNotNull null

            val attrs = obj["attributes"]?.jsonObject
                ?.get("attribute")?.jsonArray
                ?.associate { a ->
                    val ao = a.jsonObject
                    ao["name"]?.jsonPrimitive?.content to
                        ao["values"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                } ?: emptyMap()

            val title = attrs["HEADING"]?.trim()
                ?: obj["description"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null

            val priceAmountStr = attrs["PRICE"] ?: return@mapNotNull null
            val priceAmount = priceAmountStr.toLongOrNull() ?: return@mapNotNull null
            val price = Money(priceAmount * 100, Currency.EUR)

            val seoUrl = attrs["SEO_URL"] ?: "kaufen-und-verkaufen/d/$id/"
            val url = "https://www.willhaben.at/iad/$seoUrl"

            val imageUrl = obj["advertImageList"]?.jsonObject
                ?.get("advertImage")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("mainImageUrl")?.jsonPrimitive?.contentOrNull

            val locationStr = attrs["LOCATION"]
            val location = locationStr?.let { Location.parse(it) }

            // Posting date: willhaben carries a PUBLISHED epoch-millis attribute (with a
            // PUBLISHED_String fallback). Lets the UI show how old the ad is.
            val listingDate = attrs["PUBLISHED"]?.toLongOrNull()?.let {
                runCatching { Instant.fromEpochMilliseconds(it) }.getOrNull()
            }

            // Shipping: Willhaben uses "DELIVERY" or "SHIPPING" attribute, or "POSTAGE"
            val shippingAttr = attrs["DELIVERY"] ?: attrs["SHIPPING"] ?: attrs["POSTAGE"]
            val shipping = when {
                shippingAttr != null && shippingAttr.contains("Versand", true) -> Shipping(available = true)
                shippingAttr != null && shippingAttr.contains("Selbstabholung", true) -> Shipping(pickup = true)
                else -> null // Willhaben is mostly local pickup
            }

            // Structured car specs from the advert attributes (car listings only carry these). Names
            // are tried defensively — an absent attribute just leaves the field to text inference.
            val year = (attrs["YEAR_MODEL"] ?: attrs["FIRST_REGISTRATION"] ?: attrs["INITIAL_REGISTRATION"])
                ?.let { Regex("(19|20)\\d{2}").find(it)?.value?.toIntOrNull() }
            val mileageKm = (attrs["MILEAGE"] ?: attrs["ODOMETER"])
                ?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..2_000_000 }
            val powerKw = (attrs["ENGINE/POWER"] ?: attrs["MOTOR/POWER"] ?: attrs["POWER"])
                ?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }?.takeIf { it in 10..1500 }
            val fuel = Fuel.parse(attrs["ENGINE/FUEL"] ?: attrs["FUEL"])
            val gearbox = when ((attrs["TRANSMISSION"] ?: attrs["ENGINE/GEARBOX"] ?: attrs["GEARBOX"])?.lowercase()) {
                "automatik", "automatic", "automatikgetriebe" -> Transmission.AUTOMATIC
                "schaltgetriebe", "manuell", "manual" -> Transmission.MANUAL
                else -> null
            }
            val vehicle = if (year != null || mileageKm != null || powerKw != null || fuel != null || gearbox != null)
                VehicleTextParser.verifiedByPresence(
                    VehicleInfo(firstRegYear = year, mileageKm = mileageKm, powerKw = powerKw, fuel = fuel, gearbox = gearbox),
                )
            else null

            Listing(
                id = "${platformId.name}:$id",
                platformId = platformId,
                externalId = id,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                location = location,
                shipping = shipping,
                condition = Condition.USED,
                listingDate = listingDate,
                vehicle = vehicle,
                scrapedAt = now,
            )
        }
    }

    companion object {
        // CarQueryResolver make slug → willhaben's numeric CAR_MODEL/MAKE id (read from its own
        // car filter navigator). Makes willhaben doesn't list, or that CarQueryResolver doesn't
        // resolve, simply aren't searched on willhaben (it falls back to skipping cars there).
        private val WILLHABEN_MAKE_IDS = mapOf(
            "volkswagen" to 1065, "audi" to 1003, "bmw" to 1005, "mercedes-benz" to 1036,
            "opel" to 1043, "ford" to 1017, "skoda" to 1057, "seat" to 1056, "cupra" to 10026,
            "renault" to 1051, "peugeot" to 1045, "citroen" to 1010, "fiat" to 1016,
            "toyota" to 1062, "hyundai" to 1020, "kia" to 1025, "mazda" to 1035, "nissan" to 1042,
            "volvo" to 1064, "porsche" to 1048, "dacia" to 1011, "suzuki" to 1061,
            "mitsubishi" to 1040, "honda" to 1018, "jeep" to 1024, "land-rover" to 1029,
            "jaguar" to 1023, "alfa-romeo" to 1000, "chevrolet" to 1008, "lexus" to 1030,
            "byd" to 10034, "iveco" to 1022,
        )
    }
}
