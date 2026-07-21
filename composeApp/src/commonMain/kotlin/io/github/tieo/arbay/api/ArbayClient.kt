package io.github.tieo.arbay.api

import io.github.tieo.arbay.appSecrets
import io.github.tieo.arbay.defaultServerUrl
import io.github.tieo.arbay.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A non-2xx from the server, carrying the server's own plain-text reason as the message so the
 *  UI can show it verbatim instead of a serializer's "unexpected token" noise. */
class ArbayApiException(message: String) : Exception(message)

class ArbayClient(
    baseUrl: String = appSecrets().serverUrl ?: defaultServerUrl(),
) {
    var baseUrl: String = baseUrl.trimEnd('/')
        private set

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        appSecrets().authHeader?.let { auth ->
            install(DefaultRequest) {
                header(HttpHeaders.Authorization, auth)
            }
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


    @Serializable
    data class MakeModelsDto(val makeId: String = "", val models: List<CarModelNode> = emptyList())

    @Serializable
    data class CrawlerConfigDto(
        val maxResultsPerPlatform: Int = 60,
        val maxPages: Int = 8,
        val ebayItemsPerPage: Int = 120,
        val sortByPrice: Boolean = true,
    )

    suspend fun getExchangeRates(): Map<String, Double> =
        client.get("$baseUrl/api/crawler/exchange-rates").body()

    suspend fun getCarTaxonomy(): CarTaxonomy =
        client.get("$baseUrl/api/car-taxonomy").body()

    /** Live model catalog for a make, probed from the site and cached server-side. Returns null
     *  on any error so the caller keeps the bundled models. */
    suspend fun getCarModels(makeId: String): List<CarModelNode>? = try {
        client.get("$baseUrl/api/car-taxonomy/models/$makeId").body<MakeModelsDto>().models
    } catch (_: Exception) {
        null
    }

    suspend fun getCrawlerConfig(): CrawlerConfigDto =
        client.get("$baseUrl/api/crawler/config").body()

    suspend fun updateCrawlerConfig(maxResultsPerPlatform: Int, sortByPrice: Boolean) {
        client.post("$baseUrl/api/crawler/config") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(CrawlerConfigDto(maxResultsPerPlatform = maxResultsPerPlatform, sortByPrice = sortByPrice))
        }
    }

    suspend fun crawlerSearch(query: String, platform: PlatformId? = null, limit: Int = 50, sold: Boolean = false): List<Listing> =
        client.get("$baseUrl/api/crawler/search") {
            parameter("q", query)
            platform?.let { parameter("platform", it.name) }
            parameter("limit", limit)
            if (sold) parameter("sold", "true")
        }.body()

    // ── Free Items ────────────────────────────────────────────────────────────

    suspend fun getFreeItemProfile(): FreeItemProfile? = try {
        val response = client.get("$baseUrl/api/free-items/profile")
        if (response.status == HttpStatusCode.NoContent) null else response.body()
    } catch (_: Exception) { null }

    suspend fun setFreeItemProfile(description: String, location: String? = null, radiusKm: Int = 30, trackingEnabled: Boolean = false) {
        client.post("$baseUrl/api/free-items/profile") {
            contentType(ContentType.Application.Json)
            setBody(FreeItemProfile(description = description, location = location, radiusKm = radiusKm, trackingEnabled = trackingEnabled))
        }
    }

    suspend fun submitFreeItemFeedback(
        listingId: String, title: String, action: FeedbackAction,
        url: String? = null, imageUrl: String? = null, locationText: String? = null,
        description: String? = null, relevanceScore: Double? = null,
        remainingIds: List<String> = emptyList(),
    ): FeedbackResponse? = try {
        client.post("$baseUrl/api/free-items/feedback") {
            contentType(ContentType.Application.Json)
            setBody(ListingFeedback(
                listingId = listingId, title = title, action = action,
                url = url, imageUrl = imageUrl, locationText = locationText,
                description = description, relevanceScore = relevanceScore,
                remainingIds = remainingIds,
            ))
        }.body<FeedbackResponse>()
    } catch (_: Exception) { null }

    suspend fun undoFreeItemFeedback(listingId: String) {
        client.delete("$baseUrl/api/free-items/feedback/$listingId")
    }

    suspend fun getFreeItemInsights(): FreeItemInsights? = try {
        client.get("$baseUrl/api/free-items/insights").body()
    } catch (_: Exception) { null }

    suspend fun getFreeItemHistory(action: FeedbackAction? = null): List<FeedbackHistoryItem> = try {
        client.get("$baseUrl/api/free-items/history") {
            action?.let { parameter("action", it.name) }
        }.body()
    } catch (_: Exception) { emptyList() }

    suspend fun getFreeItemStats(): FreeItemStats? = try {
        client.get("$baseUrl/api/free-items/stats").body()
    } catch (_: Exception) { null }

    suspend fun getNewMatches(): List<NewMatch> = try {
        client.get("$baseUrl/api/free-items/tracking/matches").body()
    } catch (_: Exception) { emptyList() }

    // ── Notification Settings ─────────────────────────────────────────────

    suspend fun getNotificationSettings(): NotificationSettings = try {
        client.get("$baseUrl/api/free-items/notifications/settings").body()
    } catch (_: Exception) { NotificationSettings() }

    suspend fun updateNotificationSettings(settings: NotificationSettings) {
        client.post("$baseUrl/api/free-items/notifications/settings") {
            contentType(ContentType.Application.Json)
            setBody(settings)
        }
    }

    suspend fun pollNow(): PollResult = try {
        client.get("$baseUrl/api/free-items/notifications/poll").body()
    } catch (_: Exception) { PollResult() }

    suspend fun getLastPollResult(): PollResult? = try {
        val response = client.get("$baseUrl/api/free-items/notifications/last")
        if (response.status == HttpStatusCode.NoContent) null else response.body()
    } catch (_: Exception) { null }

    // ── Model Arena ─────────────────────────────────────────────────────────

    @Serializable
    data class ModelInfo(
        val id: String = "",
        val name: String = "",
        val trainable: String = "false",
        val active: String = "false",
        val totalPredictions: String = "0",
        val accuracy: String = "0.000",
        val precision: String = "0.000",
        val recall: String = "0.000",
        val separation: String = "0.000",
        val falseNegatives: String = "0",
    )

    @Serializable
    data class ArenaEntry(
        val modelId: String,
        val accuracy: Double = 0.0,
        val precision: Double = 0.0,
        val recall: Double = 0.0,
        val separation: Double = 0.0,
        val totalPredictions: Int = 0,
        val falseNegatives: Int = 0,
    )

    suspend fun getModels(): List<ModelInfo> = try {
        client.get("$baseUrl/api/models").body()
    } catch (_: Exception) { emptyList() }

    suspend fun getArenaLeaderboard(): List<ArenaEntry> = try {
        client.get("$baseUrl/api/models/arena").body()
    } catch (_: Exception) { emptyList() }

    suspend fun setActiveModel(modelId: String) {
        client.post("$baseUrl/api/models/active") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("modelId" to modelId))
        }
    }

    suspend fun retrainModels(): Map<String, String> = try {
        client.post("$baseUrl/api/models/retrain").body()
    } catch (_: Exception) { emptyMap() }

    suspend fun getRejectedItems(threshold: Double = 0.4, limit: Int = 50): List<RejectedItem> = try {
        client.get("$baseUrl/api/free-items/rejected") {
            parameter("threshold", threshold)
            parameter("limit", limit)
        }.body()
    } catch (_: Exception) { emptyList() }

    fun freeItemsStream(
        query: String = "",
        startPage: Int = 1,
        batchSize: Int = 10,
        radiusKm: Int? = null,
    ): Flow<CrawlerSearchEvent> = flow {
        client.prepareGet("$baseUrl/api/free-items/stream") {
            if (query.isNotBlank()) parameter("q", query)
            if (startPage > 1) parameter("startPage", startPage)
            if (batchSize != 10) parameter("batchSize", batchSize)
            radiusKm?.let { parameter("radiusKm", it) }
        }.execute { response ->
            // A non-2xx carries a plain-text reason, not the event JSON — surface it as-is
            // instead of feeding it to the JSON parser (which would report a bogus token error).
            if (!response.status.isSuccess()) throw ArbayApiException(response.bodyAsText().ifBlank { response.status.description })
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

    private val streamJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun crawlerSearchStream(
        query: String,
        platform: PlatformId? = null,
        platforms: List<PlatformId>? = null,
        filters: CarFilters? = null,
        lat: Double? = null,
        lon: Double? = null,
    ): Flow<CrawlerSearchEvent> = flow {
        client.prepareGet("$baseUrl/api/crawler/search/stream") {
            parameter("q", query)
            platform?.let { parameter("platform", it.name) }
            if (platforms != null && platform == null) {
                parameter("platforms", platforms.joinToString(",") { it.name })
            }
            // The device position, so the server can fill in each listing's distance.
            if (lat != null && lon != null) { parameter("lat", lat); parameter("lon", lon) }
            filters?.takeUnless { it.isEmpty }?.let { f ->
                // Whole filter set as one JSON param — covers the multi-selects; the server
                // mirrors the native-param fields itself.
                parameter("carFilters", streamJson.encodeToString(CarFilters.serializer(), f))
            }
        }.execute { response ->
            if (!response.status.isSuccess()) throw ArbayApiException(response.bodyAsText().ifBlank { response.status.description })
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
