package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*

class IdealoCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.IDEALO

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        // Akamai blocks search page (503) for both curl and Playwright Chromium.
        // Strategy: use /suggest API (accessible) + concurrent product page title scraping.
        // Page title format: "Product Name ab 123,45 € (Monat Jahr Preise) | idealo.de"
        // curl_cffi Chrome TLS impersonation passes Akamai for both suggest and product pages.
        val jsonStr = CurlCffiClient.idealoSearch(query.positiveText)
        val listings = parseResults(jsonStr)
        // The script falls back to Idealo's own category for a query it has no product suggestions
        // for, and those products carry the category's name rather than the search's words.
        if (listings.isNotEmpty() && listings.none { it.title.contains(query.positiveText, ignoreCase = true) }) {
            emitAnsweredFromCategory()
        }
        return listings
    }

    private fun parseResults(jsonStr: String): List<Listing> {
        val now = Clock.System.now()
        val arr = try {
            json.parseToJsonElement(jsonStr).jsonArray
        } catch (_: Exception) {
            return emptyList()
        }

        return arr.mapNotNull { elem ->
            val obj = elem.jsonObject
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null

            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val externalId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

            val priceText = obj["price_text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val price = Money.parse("$priceText €") ?: return@mapNotNull null

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                condition = Condition.NEW,
                imageUrls = emptyList(),
                scrapedAt = now,
            )
        }
    }
}
