package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandHttp
import io.ktor.client.*
import java.net.URLEncoder
import kotlin.time.Clock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val json = Json { ignoreUnknownKeys = true }

/**
 * Refurbed's search: its own autosuggest answers the products for the words (names and pages, no
 * prices), and each product's page names its lowest price ("price2") and its first picture.
 * Fetched through Neuland's client that greets the site as Chrome does.
 */
class RefurbedCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.REFURBED

    private val home = "https://www.refurbed.de/"

    override suspend fun search(query: SearchQuery): List<Listing> {
        val words = URLEncoder.encode(query.positiveText, Charsets.UTF_8)
        val raw = NeulandHttp.fetch(
            "https://www.refurbed.de/search-autosuggest/?query=$words&category_types=&limit=8&sort_by=popular",
            headers = mapOf(
                "Accept" to "application/json, */*",
                "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors",
                "Referer" to home,
            ),
        )
        val suggestions = try {
            json.parseToJsonElement(raw).jsonObject["suggestions"]?.jsonArray.orEmpty()
        } catch (e: Exception) {
            throw unreadablePage("Refurbed", "its autosuggest answered no JSON", raw, e)
        }
        val products = suggestions.take(8).mapNotNull { s ->
            val o = s.jsonObject
            val name = o["product_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val link = o["product_link"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(name, link, link.trim('/').substringAfterLast('/'))
        }
        val now = Clock.System.now()
        // Five product pages at a time, as a person opening them in tabs.
        val turns = Semaphore(5)
        return coroutineScope {
            products.map { (name, link, slug) ->
                async {
                    turns.withPermit {
                        val url = "https://www.refurbed.de$link"
                        val page = runCatching { NeulandHttp.fetch(url, headers = mapOf("Sec-Fetch-Site" to "same-origin", "Referer" to home)) }.getOrNull()
                            ?: return@withPermit null
                        val (cents, image) = productPage(page)
                        cents ?: return@withPermit null
                        Listing(
                            id = "${platformId.name}:$slug",
                            platformId = platformId,
                            externalId = slug,
                            url = url,
                            title = name,
                            price = Money(cents, Currency.EUR),
                            condition = Condition.REFURBISHED,
                            imageUrls = listOfNotNull(image),
                            shipping = Shipping(free = true), // Refurbed: free shipping included
                            scrapedAt = now,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    companion object {
        private val withPrice = Regex(""""price"\s*:\s*"[\d.]+"\s*,\s*"price2"\s*:\s*"([\d.]+)"""")
        private val lowest = Regex(""""price2"\s*:\s*"([\d.]+)"""")
        private val picture = Regex(""""image"\s*:\s*"(https://files\.refurbed\.com/[^"]+)"""")

        /** A product page's lowest price (its "price2") in cents, and its first picture. */
        internal fun productPage(page: String): Pair<Long?, String?> {
            val price = (withPrice.find(page) ?: lowest.find(page))?.groupValues?.get(1)?.toDoubleOrNull()?.let { Math.round(it * 100) }
            return price to picture.find(page)?.groupValues?.get(1)
        }
    }
}
