package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class RefurbedItem(
    val title: String,
    val url: String,
    val id: String,
    @SerialName("price_cents") val priceCents: Long,
    @SerialName("image_url") val imageUrl: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

class RefurbedCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.REFURBED

    override suspend fun search(query: SearchQuery): List<Listing> {
        val raw = CurlCffiClient.refurbedSearch(query.positiveText)
        val items = runCatching { json.decodeFromString<List<RefurbedItem>>(raw) }.getOrElse { return emptyList() }
        val now = Clock.System.now()

        return items.map { item ->
            Listing(
                id = "${platformId.name}:${item.id}",
                platformId = platformId,
                externalId = item.id,
                url = item.url,
                title = item.title,
                price = Money(item.priceCents, Currency.EUR),
                condition = Condition.REFURBISHED,
                imageUrls = listOfNotNull(item.imageUrl),
                shipping = Shipping(free = true), // Refurbed: free shipping included
                scrapedAt = now,
            )
        }
    }
}
