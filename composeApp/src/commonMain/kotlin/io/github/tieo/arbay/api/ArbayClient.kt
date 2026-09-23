package io.github.tieo.arbay.api

import io.github.tieo.arbay.appSecrets
import io.github.tieo.arbay.defaultServerUrl
import io.github.tieo.arbay.loadDeviceSettings
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.saveDeviceSettings
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A non-2xx from the server, carrying the server's own plain-text reason as the message so the
 *  UI can show it verbatim instead of a serializer's "unexpected token" noise. */
class ArbayApiException(message: String) : Exception(message)

class ArbayClient(
    // The address this device was last pointed at, else the configured one. A server chosen in
    // Settings is a property of the device, so it outlives the process that chose it.
    baseUrl: String = loadDeviceSettings()["serverUrl"] ?: appSecrets().serverUrl ?: defaultServerUrl(),
) : AutoCloseable {
    var baseUrl: String = baseUrl.trimEnd('/')
        private set

    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        // Every answer that is not a success is an error with the server's own reason, raised
        // here once. Unchecked, a 500 or the sign-in page in front of the server failed later as a
        // serializer's "unexpected token", and a delete that was refused looked like one that worked.
        HttpResponseValidator {
            validateResponse { response ->
                // The sign-in in front of the server answers an unsigned request with its own login
                // page and a 200, which is not the server answering. The server itself never sends
                // a web page, so one arriving means something in between answered instead.
                if (response.contentType()?.match(ContentType.Text.Html) == true) {
                    val host = response.call.request.url.host
                    throw ArbayApiException(
                        if (host != Url(baseUrl).host) "The server asked to sign in first ($host), so it was not reached"
                        else "The server answered with a web page instead of data",
                    )
                }
                if (!response.status.isSuccess()) {
                    val reason = runCatching { response.bodyAsText() }.getOrNull()?.take(300)
                    throw ArbayApiException(reason?.takeIf { it.isNotBlank() } ?: response.status.description)
                }
            }
        }
        // A server that takes the connection and then says nothing must not leave a screen
        // loading for ever. The two streams lift the overall limit (a search runs for minutes)
        // and keep the silence limit, which the server's keepalive line stays well inside.
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
            requestTimeoutMillis = 60_000
        }
        appSecrets().authHeader?.let { auth ->
            install(DefaultRequest) {
                header(HttpHeaders.Authorization, auth)
            }
        }
    }

    override fun close() = client.close()

    /**
     * A read the screen can do without: its value, or [fallback] when the server could not give
     * one. A caller that was cancelled is not a read that failed, so its cancellation carries on
     * instead of coming back as the fallback.
     */
    private inline fun <T> orElse(fallback: T, read: () -> T): T = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        fallback
    }

    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl.trimEnd('/')
        saveDeviceSettings(loadDeviceSettings() + ("serverUrl" to baseUrl))
    }

    suspend fun getProducts(): List<TrackedProduct> =
        client.get("$baseUrl/api/products").body()

    /** What every market can do, declared by its crawler. */
    suspend fun getMarketCapabilities(): List<MarketCapability> =
        client.get("$baseUrl/api/markets").body()

    /** What each saved search has found since it was last opened, and whether it is watched. */
    suspend fun getSavedSearchStatus(): List<SavedSearchStatus> =
        client.get("$baseUrl/api/products/status").body()

    /** The listings a saved search turned up since it was last looked at, as the watch stored them.
     *  No crawl runs to answer this, and listings the platform has since removed still come back. */
    suspend fun getNewListings(id: String): List<Listing> =
        client.get("$baseUrl/api/products/$id/new").body<List<Listing>>().withServerImages()

    /** Tell the server a saved search was opened, so what was waiting counts as seen. */
    suspend fun markSavedSearchOpened(id: String) {
        client.post("$baseUrl/api/products/$id/opened")
    }

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
    }.body<List<Listing>>().withServerImages()

    suspend fun searchListings(query: String, limit: Int = 50): List<Listing> =
        client.get("$baseUrl/api/listings/search") {
            parameter("q", query)
            parameter("limit", limit)
        }.body<List<Listing>>().withServerImages()

    suspend fun getPriceHistory(query: String, platform: PlatformId? = null): List<Listing> =
        client.get("$baseUrl/api/listings/price-history") {
            parameter("q", query)
            platform?.let { parameter("platform", it.name) }
        }.body<List<Listing>>().withServerImages()


    @Serializable
    data class CrawlerConfigDto(
        val maxResultsPerPlatform: Int = 60,
        val maxPages: Int = 8,
        val ebayItemsPerPage: Int = 120,
    )

    suspend fun getExchangeRates(): Map<String, Double> =
        client.get("$baseUrl/api/crawler/exchange-rates").body()

    /** Where the buyer is and what import VAT they pay. Held by the server so the app's prices and
     *  the server's own notification subfilters are worked out the same way. */
    suspend fun getImportSettings(): ImportSettings =
        client.get("$baseUrl/api/settings/import").body()

    suspend fun updateImportSettings(settings: ImportSettings): ImportSettings =
        client.post("$baseUrl/api/settings/import") {
            contentType(ContentType.Application.Json)
            setBody(settings)
        }.body()

    /** Ask to be told before one auction ends, or stop asking. */
    suspend fun setAuctionReminder(reminder: AuctionReminder): AuctionReminder =
        client.post("$baseUrl/api/auctions/reminders") {
            contentType(ContentType.Application.Json)
            setBody(reminder)
        }.body()

    suspend fun clearAuctionReminder(listingId: String) {
        client.delete("$baseUrl/api/auctions/reminders/${listingId.encodeURLPathPart()}")
    }

    /** Where a place is, for measuring listings against a home town. Null when the server's index
     *  does not know it. */
    suspend fun geocode(place: String): Pair<Double, Double>? {
        val body = orElse(null) { client.get("$baseUrl/api/geocode") { parameter("q", place) }.body<Map<String, Double>>() }
            ?: return null
        val lat = body["latitude"] ?: return null
        val lon = body["longitude"] ?: return null
        return lat to lon
    }

    /**
     * Everything one listing's own page says, for a market that keeps it there rather than on the
     * card it was found through: the seller's whole text, the specs the card omits, and where the
     * thing is. eBay, for one, states a location on the item page and never on a search card.
     *
     * One page load on the server, cached there, asked for the listing being read. Null when the
     * market's page adds nothing or could not be read.
     */
    suspend fun listingDetail(listing: Listing): ListingDetail? = orElse(null) {
        val response = client.get("$baseUrl/api/crawler/listing-detail") {
            parameter("url", listing.url)
            parameter("platform", listing.platformId.name)
            parameter("id", listing.id)
        }
        if (response.status == HttpStatusCode.NoContent) null else response.body<ListingDetail>()
    }

    suspend fun getMarketSettings(): MarketSettings =
        client.get("$baseUrl/api/settings/markets").body()

    suspend fun updateMarketSettings(settings: MarketSettings): MarketSettings =
        client.post("$baseUrl/api/settings/markets") {
            contentType(ContentType.Application.Json)
            setBody(settings)
        }.body()

    suspend fun getCarTaxonomy(): CarTaxonomy =
        client.get("$baseUrl/api/car-taxonomy").body()

    /** The offline copy of a listing that may no longer exist on its own platform — its own
     *  fields plus images mirrored on our server. Null when it was never archived. */
    suspend fun getArchivedListing(id: String): Listing? = orElse(null) {
        client.get("$baseUrl/api/archive/listings/$id").body<Listing>().withServerImages()
    }

    /**
     * An archived listing carries its mirrored images as paths on the server ("/api/archive/…"),
     * stored that way so the files survive the server moving or changing address. A path is not
     * something an image loader can fetch, and one that reaches a screen renders as a blank frame,
     * so every listing coming back from the server is pointed at [baseUrl] here — the one place
     * that knows which server this device is talking to.
     */
    private fun Listing.withServerImages(): Listing =
        if (imageUrls.none { it.startsWith("/") }) this
        else copy(imageUrls = imageUrls.map { if (it.startsWith("/")) "$baseUrl$it" else it })

    private fun List<Listing>.withServerImages(): List<Listing> = map { it.withServerImages() }

    suspend fun getCrawlerConfig(): CrawlerConfigDto =
        client.get("$baseUrl/api/crawler/config").body()

    suspend fun updateCrawlerConfig(maxResultsPerPlatform: Int) {
        client.post("$baseUrl/api/crawler/config") {
            contentType(io.ktor.http.ContentType.Application.Json)
            setBody(CrawlerConfigDto(maxResultsPerPlatform = maxResultsPerPlatform))
        }
    }

    suspend fun crawlerSearch(
        query: String, platform: PlatformId? = null, limit: Int = 50, sold: Boolean = false,
        carFilters: io.github.tieo.arbay.model.CarFilters? = null,
        excludeKeywords: List<String> = emptyList(), aliases: List<String> = emptyList(),
    ): List<Listing> =
        client.get("$baseUrl/api/crawler/search") {
            // Every market crawled before the one answer comes back, with nothing said meanwhile:
            // bounded by the server's own search budget rather than the default minute.
            timeout {
                requestTimeoutMillis = 420_000
                socketTimeoutMillis = 420_000
            }
            parameter("q", query)
            platform?.let { parameter("platform", it.name) }
            parameter("limit", limit)
            if (sold) parameter("sold", "true")
            carFilters?.takeUnless { it.isEmpty }?.let {
                parameter("carFilters", streamJson.encodeToString(io.github.tieo.arbay.model.CarFilters.serializer(), it))
            }
            if (excludeKeywords.isNotEmpty()) parameter("excludeKeywords", excludeKeywords.joinToString(","))
            if (aliases.isNotEmpty()) parameter("aliases", aliases.joinToString(","))
        }.body()

    // ── Free Items ────────────────────────────────────────────────────────────

    suspend fun getFreeItemProfile(): FreeItemProfile? = orElse(null) {
        val response = client.get("$baseUrl/api/free-items/profile")
        if (response.status == HttpStatusCode.NoContent) null else response.body()
    }

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
    ): FeedbackResponse =
        client.post("$baseUrl/api/free-items/feedback") {
            contentType(ContentType.Application.Json)
            setBody(ListingFeedback(
                listingId = listingId, title = title, action = action,
                url = url, imageUrl = imageUrl, locationText = locationText,
                description = description, relevanceScore = relevanceScore,
                remainingIds = remainingIds,
            ))
        }.body<FeedbackResponse>()

    suspend fun undoFreeItemFeedback(listingId: String) {
        client.delete("$baseUrl/api/free-items/feedback/$listingId")
    }

    suspend fun getFreeItemInsights(): FreeItemInsights? = orElse(null) {
        client.get("$baseUrl/api/free-items/insights").body()
    }

    suspend fun getFreeItemHistory(action: FeedbackAction? = null): List<FeedbackHistoryItem> = orElse(emptyList()) {
        client.get("$baseUrl/api/free-items/history") {
            action?.let { parameter("action", it.name) }
        }.body()
    }

    suspend fun getFreeItemStats(): FreeItemStats? = orElse(null) {
        client.get("$baseUrl/api/free-items/stats").body()
    }

    suspend fun getNewMatches(): List<NewMatch> = orElse(emptyList()) {
        client.get("$baseUrl/api/free-items/tracking/matches").body()
    }

    // ── Notification Settings ─────────────────────────────────────────────

    suspend fun getNotificationSettings(): NotificationSettings = orElse(NotificationSettings()) {
        client.get("$baseUrl/api/free-items/notifications/settings").body()
    }

    suspend fun updateNotificationSettings(settings: NotificationSettings) {
        client.post("$baseUrl/api/free-items/notifications/settings") {
            contentType(ContentType.Application.Json)
            setBody(settings)
        }
    }

    /** What the server has waiting to be raised as notifications. Throws when it cannot be asked,
     *  so the background worker retries instead of recording a poll that never happened.
     *  [acknowledged] is the last delivery this device raised, which the server then lets go. */
    suspend fun pollNow(acknowledged: Long): PollResult =
        client.get("$baseUrl/api/free-items/notifications/poll") {
            parameter("ack", acknowledged)
            // The server runs its free-item check before it answers, a crawl of up to two minutes.
            timeout {
                requestTimeoutMillis = 180_000
                socketTimeoutMillis = 180_000
            }
        }.body()

    suspend fun getLastPollResult(): PollResult? = orElse(null) {
        val response = client.get("$baseUrl/api/free-items/notifications/last")
        if (response.status == HttpStatusCode.NoContent) null else response.body()
    }

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

    suspend fun getModels(): List<ModelInfo> = orElse(emptyList()) {
        client.get("$baseUrl/api/models").body()
    }

    suspend fun getArenaLeaderboard(): List<ArenaEntry> = orElse(emptyList()) {
        client.get("$baseUrl/api/models/arena").body()
    }

    suspend fun setActiveModel(modelId: String) {
        client.post("$baseUrl/api/models/active") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("modelId" to modelId))
        }
    }

    suspend fun retrainModels(): Map<String, String> =
        client.post("$baseUrl/api/models/retrain").body()

    suspend fun getRejectedItems(threshold: Double = 0.4, limit: Int = 50): List<RejectedItem> = orElse(emptyList()) {
        client.get("$baseUrl/api/free-items/rejected") {
            parameter("threshold", threshold)
            parameter("limit", limit)
        }.body()
    }

    fun freeItemsStream(
        query: String = "",
        startPage: Int = 1,
        batchSize: Int = 10,
        radiusKm: Int? = null,
    ): Flow<CrawlerSearchEvent> = flow {
        client.prepareGet("$baseUrl/api/free-items/stream") {
            // No keepalive line on this stream, and one page through the browser tiers can take
            // minutes, so it may stay silent that long.
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 300_000
            }
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

    /** Terms this search could be sent to markets in other languages, for the searcher to look at
     *  and accept. Nothing here reaches a market: what a market is asked is what the search
     *  carries, and it carries only what someone put there. */
    suspend fun termSuggestions(query: String, languages: List<String>): TermSuggestions =
        client.get("$baseUrl/api/crawler/term-suggestions") {
            parameter("q", query)
            if (languages.isNotEmpty()) parameter("languages", languages.joinToString(","))
        }.body()

    fun crawlerSearchStream(
        query: String,
        platform: PlatformId? = null,
        platforms: List<PlatformId>? = null,
        filters: CarFilters? = null,
        excludeKeywords: List<String> = emptyList(),
        aliases: List<String> = emptyList(),
        // How far this search may travel from the typed words. Sent per search, so the server
        // never decides on its own what a market gets asked.
        reach: SearchReach = SearchReach(),
        // Where the search is centred and how far it reaches, for the markets that take one.
        near: String? = null,
        radiusKm: Int? = null,
        lat: Double? = null,
        lon: Double? = null,
    ): Flow<CrawlerSearchEvent> = flow {
        client.prepareGet("$baseUrl/api/crawler/search/stream") {
            timeout { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
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
            if (excludeKeywords.isNotEmpty()) parameter("excludeKeywords", excludeKeywords.joinToString(","))
            if (aliases.isNotEmpty()) parameter("aliases", aliases.joinToString(","))
            near?.takeIf { it.isNotBlank() }?.let { parameter("near", it) }
            radiusKm?.takeIf { it > 0 }?.let { parameter("radiusKm", it) }
            if (!reach.isDefault) {
                parameter("reach", streamJson.encodeToString(SearchReach.serializer(), reach))
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
