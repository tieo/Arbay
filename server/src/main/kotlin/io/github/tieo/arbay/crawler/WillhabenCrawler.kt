package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

class WillhabenCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.WILLHABEN

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        // A car query goes to willhaben's used-car vertical (Motor), not the general marktplatz —
        // the latter's keyword search doesn't reach vehicle stock. The __NEXT_DATA__ shape
        // (searchResult.advertSummaryList.advertSummary) is the same, so the parser is shared;
        // vehicle specs are left to the central text enrichment (soft-pass, per the provenance model).
        // NOTE: willhaben's used-car vertical (gebrauchtwagen/auto/gebrauchtwagenboerse) IGNORES the
        // ?keyword= param — it filters by numeric make/model IDs (carmake=/carmodel=) instead, so a
        // keyword search returns a default car set (a "Crafter" query gave 22 Tiguans/Audis). Until
        // those IDs are mapped, willhaben is a general-marktplatz crawler only and is NOT a car market.
        val url = "https://www.willhaben.at/iad/kaufen-und-verkaufen/marktplatz?keyword=${query.positiveText.encodeUrl()}"
        // A current Chrome TLS fingerprint (rnet, step 2 of the chain) is served the full
        // __NEXT_DATA__ page; the browser tiers remain as a fallback.
        val html = fetchWithFallback(
            client, url, "willhaben",
            primeUrl = "https://www.willhaben.at",
            extraWaitMs = 1500,
        )
        return parseSearchResults(html)
    }

    /** Diagnostic only: fetch a willhaben car page and surface the make/model filter navigator from
     *  __NEXT_DATA__ (each option carries its own working URL/ID), to learn the car-search URL shape. */
    suspend fun debugRaw(url: String): String {
        val html = fetchWithFallback(client, url, "willhaben-debug", primeUrl = "https://www.willhaben.at", extraWaitMs = 1500)
        val data = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return "no __NEXT_DATA__"
        val sb = StringBuilder("len=${data.length} titles=${parseSearchResults(html).size}\n")
        Regex("\"label\":\"([^\"]{1,40})\"[^}]{0,140}?CAR_MODEL/MODEL\"[^}]{0,60}?\"value\":\"(\\d+)\"")
            .findAll(data).map { "${it.groupValues[1]}=${it.groupValues[2]}" }.distinct().take(120)
            .forEach { sb.append(it).append("  ") }
        return sb.toString().take(3800)
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
                scrapedAt = now,
            )
        }
    }
}
