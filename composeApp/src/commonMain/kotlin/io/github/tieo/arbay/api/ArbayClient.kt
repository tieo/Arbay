package io.github.tieo.arbay.api

import io.github.tieo.arbay.defaultServerUrl
import io.github.tieo.arbay.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class ArbayClient(
    baseUrl: String = defaultServerUrl(),
) {
    var baseUrl: String = baseUrl
        private set

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl.trimEnd('/')
    }

    suspend fun getProducts(): List<TrackedProduct> =
        client.get("$baseUrl/api/products").body()

    suspend fun getProduct(id: String): TrackedProduct =
        client.get("$baseUrl/api/products/$id").body()

    suspend fun createProduct(product: TrackedProduct): TrackedProduct =
        client.post("$baseUrl/api/products") {
            contentType(ContentType.Application.Json)
            setBody(product)
        }.body()

    suspend fun updateProduct(product: TrackedProduct): TrackedProduct =
        client.put("$baseUrl/api/products/${product.id}") {
            contentType(ContentType.Application.Json)
            setBody(product)
        }.body()

    suspend fun deleteProduct(id: String) {
        client.delete("$baseUrl/api/products/$id")
    }

    suspend fun getListings(
        platform: PlatformId? = null,
        sold: Boolean? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<Listing> = client.get("$baseUrl/api/listings") {
        platform?.let { parameter("platform", it.name) }
        sold?.let { parameter("sold", it) }
        parameter("limit", limit)
        parameter("offset", offset)
    }.body()

    suspend fun searchListings(query: String, limit: Int = 50): List<Listing> =
        client.get("$baseUrl/api/listings/search") {
            parameter("q", query)
            parameter("limit", limit)
        }.body()

    suspend fun getPriceHistory(query: String, platform: PlatformId? = null): List<Listing> =
        client.get("$baseUrl/api/listings/price-history") {
            parameter("q", query)
            platform?.let { parameter("platform", it.name) }
        }.body()

    suspend fun getAlerts(unreadOnly: Boolean = false, limit: Int = 50): List<Alert> =
        client.get("$baseUrl/api/alerts") {
            parameter("unread", unreadOnly)
            parameter("limit", limit)
        }.body()

    suspend fun markAlertRead(id: String): Alert =
        client.post("$baseUrl/api/alerts/$id/read").body()

    suspend fun markAllAlertsRead() {
        client.post("$baseUrl/api/alerts/read-all")
    }

    suspend fun crawlerSearch(query: String, platform: PlatformId? = null, limit: Int = 50, sold: Boolean = false): List<Listing> =
        client.get("$baseUrl/api/crawler/search") {
            parameter("q", query)
            platform?.let { parameter("platform", it.name) }
            parameter("limit", limit)
            if (sold) parameter("sold", "true")
        }.body()

    private val streamJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun crawlerSearchStream(query: String, platform: PlatformId? = null, platforms: List<PlatformId>? = null): Flow<CrawlerSearchEvent> = flow {
        client.prepareGet("$baseUrl/api/crawler/search/stream") {
            parameter("q", query)
            platform?.let { parameter("platform", it.name) }
            if (platforms != null && platform == null) {
                parameter("platforms", platforms.joinToString(",") { it.name })
            }
        }.execute { response ->
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    val event = streamJson.decodeFromString<CrawlerSearchEvent>(trimmed)
                    emit(event)
                }
            }
        }
    }
}
