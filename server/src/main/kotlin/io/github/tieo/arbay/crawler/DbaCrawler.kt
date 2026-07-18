package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/**
 * Crawler for dba.dk, the Danish Schibsted classifieds site.
 *
 * Two data paths are used depending on whether vehicle filters are active:
 *
 * Unfiltered: fetches the car search page (/mobility/search/car?q=...) and parses the
 * server-rendered `application/ld+json` block (`script#seoStructuredData`,
 * schema.org `ItemList`/`Product`). No `__NEXT_DATA__` or other client-state script
 * carries the listings on this site.
 *
 * Filtered: calls the internal JSON search API at
 * /mobility/search/api/search/SEARCH_ID_CAR_USED, which honours `year_from`, `year_to`,
 * `mileage_to`, `price_to`/`price_from`, `engine_effect_from`, and `transmission` as
 * URL query parameters. The API is the same backend the React SPA calls; the HTML page
 * ignores all filter params in its seoStructuredData rendering.
 *
 * Prices are in DKK. Engine power is in HP (hk) — the API uses HP, not kW.
 * Transmission: 1 = Manuelt, 2 = Automatisk.
 */
class DbaCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.DBA

    companion object {
        private const val SEARCH_API =
            "https://www.dba.dk/mobility/search/api/search/SEARCH_ID_CAR_USED"

        /** kW to metric horsepower (1 kW = 1.35962 PS). DBA uses HP (hk). */
        private fun kwToHp(kw: Int): Int = (kw * 1.35962).toInt()

        private val json = Json { ignoreUnknownKeys = true }
    }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val searchText = CarQueryResolver.resolve(query.positiveText)?.let { car ->
            buildString {
                append(car.makeSlug.replace("-", " "))
                car.modelSlug?.let { append(' ').append(it) }
            }
        } ?: query.positiveText

        return if (hasFilters(query)) {
            collectPages(query) { page ->
                parseFromApi(fetchJson(buildApiUrl(searchText, query, page)))
            }
        } else {
            collectPages(query) { page ->
                val pageParam = if (page <= 1) "" else "&page=$page"
                val url = "https://www.dba.dk/mobility/search/car?q=${searchText.encodeUrl()}$pageParam"
                parse(fetchWithFallback(client, url, "DBA"))
            }
        }
    }

    /**
     * Fetches consecutive pages starting at query.startPage, deduplicating by externalId.
     * Stops on a fetch/parse failure (null), an empty page, a page with no new ids
     * (the site repeats the last page beyond the end), or the per-platform result cap.
     */
    private suspend fun collectPages(
        query: SearchQuery,
        fetchPage: suspend (page: Int) -> List<Listing>?,
    ): List<Listing> {
        val maxPages = query.maxPages ?: CrawlerConfig.current.maxPages
        val seen = LinkedHashMap<String, Listing>()

        for (offset in 0 until maxPages) {
            val listings = fetchPage(query.startPage + offset) ?: break

            if (listings.isEmpty()) break
            val newIds = listings.count { it.externalId !in seen }
            listings.forEach { seen.putIfAbsent(it.externalId, it) }
            if (newIds == 0) break
            if (seen.size >= CrawlerConfig.current.maxResultsPerPlatform) break
        }

        return seen.values.toList()
    }

    private fun hasFilters(query: SearchQuery) =
        query.firstRegFromYear != null ||
            query.firstRegToYear != null ||
            query.maxMileageKm != null ||
            query.minPowerKw != null ||
            query.transmission != null ||
            query.maxPrice != null ||
            query.minPrice != null

    private fun buildApiUrl(text: String, query: SearchQuery, page: Int): String = buildString {
        append(SEARCH_API)
        append("?q=").append(text.encodeUrl())
        query.firstRegFromYear?.let { append("&year_from=$it") }
        query.firstRegToYear?.let { append("&year_to=$it") }
        query.maxMileageKm?.let { append("&mileage_to=$it") }
        query.minPowerKw?.let { append("&engine_effect_from=${kwToHp(it)}") }
        query.maxPrice?.let { max ->
            val dkk = if (max.currency == Currency.DKK) max.amount / 100
            else ExchangeRates.convert(max.amount, max.currency.name, "DKK") / 100
            append("&price_to=$dkk")
        }
        query.minPrice?.let { min ->
            val dkk = if (min.currency == Currency.DKK) min.amount / 100
            else ExchangeRates.convert(min.amount, min.currency.name, "DKK") / 100
            append("&price_from=$dkk")
        }
        when (query.transmission) {
            Transmission.AUTOMATIC -> append("&transmission=2")
            Transmission.MANUAL -> append("&transmission=1")
            null -> {}
        }
        if (page > 1) append("&page=$page")
    }

    private suspend fun fetchJson(url: String): String {
        val response = client.get(url) {
            headers {
                append("User-Agent", USER_AGENT)
                append("Accept", "application/json")
            }
        }
        return response.body()
    }

    /**
     * Parses the JSON response from the internal search API at
     * /mobility/search/api/search/SEARCH_ID_CAR_USED. Each doc carries an `id`,
     * `heading`, `canonical_url`, `price.amount`/`price.currency_code`, `location`,
     * `year`, `mileage`, and optionally `image.url`. The API uses the same item IDs
     * as the HTML page, so externalId remains stable across both code paths.
     */
    internal fun parseFromApi(body: String): List<Listing>? {
        val now = Clock.System.now()
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val docs = root["docs"]?.jsonArray ?: return null

            docs.mapNotNull { el ->
                val doc = el.jsonObject

                val externalId = doc["id"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                val title = doc["heading"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                val url = doc["canonical_url"]?.jsonPrimitive?.contentOrNull
                    ?: "https://www.dba.dk/mobility/item/$externalId"

                val priceAmount = doc["price"]?.jsonObject?.get("amount")?.jsonPrimitive?.longOrNull
                    ?.takeIf { it >= 0 } ?: return@mapNotNull null
                // amount is already in the major unit (kr.), not cents
                val price = Money(priceAmount * 100, Currency.DKK)

                val imageUrl = doc["image"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull

                val city = doc["location"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() }

                val year = doc["year"]?.jsonPrimitive?.intOrNull
                val mileage = doc["mileage"]?.jsonPrimitive?.longOrNull

                val description = buildString {
                    year?.let { append(it) }
                    mileage?.let {
                        if (isNotEmpty()) append(" | ")
                        append("$it km")
                    }
                }.takeIf { it.isNotBlank() }

                val vehicle = VehicleTextParser.verifiedByPresence(VehicleInfo(
                    firstRegYear = year?.takeIf { it in 1980..2035 },
                    // range check on the Long before narrowing, so garbage values cannot wrap
                    mileageKm = mileage?.takeIf { it in 1..2_000_000 }?.toInt(),
                    fuel = Fuel.parse(
                        (doc["fuel_type"] ?: doc["fuel"] ?: doc["propellant"])?.jsonPrimitive?.contentOrNull,
                    ),
                    gearbox = when ((doc["transmission"] ?: doc["gear"])?.jsonPrimitive?.contentOrNull?.lowercase()) {
                        "automatic", "automatisk", "automatgear" -> Transmission.AUTOMATIC
                        "manual", "manuel", "manuelt" -> Transmission.MANUAL
                        else -> null
                    },
                ))

                Listing(
                    id = "${platformId.name}:$externalId",
                    platformId = platformId,
                    externalId = externalId,
                    url = url,
                    title = title,
                    price = price,
                    imageUrls = listOfNotNull(imageUrl),
                    location = Location(city = city, country = "DK"),
                    description = description,
                    scrapedAt = now,
                    vehicle = vehicle,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parse(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val structuredData = doc.selectFirst("script#seoStructuredData")?.data() ?: return emptyList()
        val now = Clock.System.now()

        return try {
            val root = json.parseToJsonElement(structuredData).jsonObject
            val items = root["mainEntity"]?.jsonObject
                ?.get("itemListElement")?.jsonArray
                ?: return emptyList()

            items.mapNotNull { element ->
                val item = element.jsonObject["item"]?.jsonObject ?: return@mapNotNull null

                val url = item["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val externalId = url.substringAfterLast('/').takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                val title = item["name"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                val priceText = item["offers"]?.jsonObject?.get("price")?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                // dba.dk only ever lists in Danish kroner, so the currency is forced
                // rather than trusted from priceCurrency (which is DKK anyway).
                val price = Money.parse(priceText, Currency.DKK) ?: return@mapNotNull null

                val imageUrl = item["image"]?.jsonPrimitive?.contentOrNull

                Listing(
                    id = "${platformId.name}:$externalId",
                    platformId = platformId,
                    externalId = externalId,
                    url = url,
                    title = title,
                    price = price,
                    imageUrls = listOfNotNull(imageUrl),
                    location = Location(country = "DK"),
                    description = item["description"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                    scrapedAt = now,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
