package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.carCriteria
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
        private val json = Json { ignoreUnknownKeys = true }
    }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val searchText = CarQueryResolver.resolveForCarSite(query.positiveText)?.let { car ->
            buildString {
                append(car.makeSlug.replace("-", " "))
                car.modelSlug?.let { append(' ').append(it) }
            }
        } ?: query.positiveText

        return if (hasFilters(query)) {
            paginate(query) { page ->
                val url = MobilityApi.searchUrl("www.dba.dk", searchText, query, page, Currency.DKK)
                MobilityApi.parseDocs(fetchJson(url), platformId, Currency.DKK, "DK", "www.dba.dk") ?: emptyList()
            }
        } else {
            paginate(query) { page ->
                val pageParam = if (page <= 1) "" else "&page=$page"
                val url = "https://www.dba.dk/mobility/search/car?q=${searchText.encodeUrl()}$pageParam"
                parse(fetchWithFallback(client, url, "DBA"))
            }
        }
    }

    private fun hasFilters(query: SearchQuery) =
        query.carCriteria.firstRegFromYear != null ||
            query.carCriteria.firstRegToYear != null ||
            query.carCriteria.maxMileageKm != null ||
            query.carCriteria.minPowerKw != null ||
            query.carCriteria.transmission != null ||
            query.maxPrice != null ||
            query.minPrice != null

    private suspend fun fetchJson(url: String): String {
        val response = client.get(url) {
            headers {
                append("User-Agent", USER_AGENT)
                append("Accept", "application/json")
            }
        }
        return response.body()
    }

    internal fun parse(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val structuredData = doc.selectFirst("script#seoStructuredData")?.data()
            ?: throw unreadablePage("DBA", "no structured data on the page", html)
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
        } catch (e: Exception) {
            throw unreadablePage("DBA", "structured data does not decode", html, e)
        }
    }
}
