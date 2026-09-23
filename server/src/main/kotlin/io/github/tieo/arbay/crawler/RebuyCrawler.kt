package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

class RebuyCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.REBUY

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val url = "https://www.rebuy.de/kaufen/suchen?q=${query.positiveText.encodeUrl()}"
        // HTTP always 429; go headless-first. Rebuy uses a VDF (proof-of-work) running in a Web Worker
        // before injecting product data as script#ry-inject. Use a 60s timeout for the VDF computation.
        val html = fetchWithBrowser(
            url, "reBuy",
            waitSelector = "script#ry-inject",
            waitTimeoutMs = 60_000.0,
            primeUrl = "https://www.rebuy.de",
            extraWaitMs = 500,
        )
        return parseSearchResults(html)
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // Rebuy embeds all product data as JSON in <script id="ry-inject">
        val scriptEl = doc.selectFirst("script#ry-inject")
            ?: throw CrawlerBlockedException("reBuy: product data not found in page (rate limited?)", ErrorType.RATE_LIMITED_429)
        val rootJson = try {
            json.parseToJsonElement(scriptEl.data()).jsonObject
        } catch (e: Exception) {
            throw unreadablePage("reBuy", "product data does not decode", html, e)
        }

        val docs = rootJson["productListViewDto"]
            ?.jsonObject?.get("searchResponse")
            ?.jsonObject?.get("products")
            ?.jsonObject?.get("docs")
            ?.jsonArray ?: return emptyList()

        return docs.mapNotNull { item ->
            val obj = item.jsonObject
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: obj["basic_name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null

            val bluePrice = obj["blue_price"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val price = Money(bluePrice, Currency.EUR)

            val inStock = obj["has_variant_in_stock"]?.jsonPrimitive?.booleanOrNull ?: true

            val catSlug = obj["category_sanitized_name"]?.jsonPrimitive?.contentOrNull ?: "produkte"
            val productSlug = obj["product_sanitized_name"]?.jsonPrimitive?.contentOrNull ?: name.lowercase().replace(" ", "-")
            val url = "https://www.rebuy.de/i,$id/$catSlug/$productSlug"

            val imageUrl = obj["thumbs_cover_urls"]?.jsonObject?.get("350x350")?.jsonPrimitive?.contentOrNull
                ?: obj["thumbs_cover_urls"]?.jsonObject?.get("200x200")?.jsonPrimitive?.contentOrNull

            if (!inStock) return@mapNotNull null

            Listing(
                id = "${platformId.name}:$id",
                platformId = platformId,
                externalId = id.toString(),
                url = url,
                title = name,
                price = price,
                condition = Condition.USED,
                imageUrls = listOfNotNull(imageUrl),
                shipping = Shipping(free = true), // reBuy: free shipping on all orders
                scrapedAt = now,
            )
        }
    }
}
