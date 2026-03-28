package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

class WillhabenCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.WILLHABEN

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val url = "https://www.willhaben.at/iad/kaufen-und-verkaufen/marktplatz?keyword=${query.positiveText.encodeUrl()}"
        // HTTP 403 since bot-detection was added; go headless-first with homepage priming
        return try {
            val html = fetchWithBrowser(
                url, "willhaben",
                primeUrl = "https://www.willhaben.at",
                extraWaitMs = 1500,
            )
            parseSearchResults(html)
        } catch (e: CrawlerBlockedException) {
            if (e.message?.contains("Object doesn't exist") == true || e.errorType == ErrorType.PARSE_ERROR) {
                // Retry once — Willhaben sometimes serves stale error responses
                val html = fetchWithBrowser(
                    url, "willhaben",
                    primeUrl = "https://www.willhaben.at",
                    extraWaitMs = 2500,
                )
                parseSearchResults(html)
            } else throw e
        }
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
                scrapedAt = now,
            )
        }
    }
}
