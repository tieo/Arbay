package io.github.tieo.arbay.api

import io.github.tieo.arbay.defaultServerUrl
import io.github.tieo.arbay.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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
}
