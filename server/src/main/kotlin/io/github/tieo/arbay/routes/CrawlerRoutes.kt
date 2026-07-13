package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.CarQueryResolver
import io.github.tieo.arbay.crawler.CrawlerBlockedException
import io.github.tieo.arbay.crawler.CrawlerConfig
import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.CrawlerStatusTracker
import io.github.tieo.arbay.crawler.ErrorSnapshotStore
import io.github.tieo.arbay.crawler.ExchangeRates
import io.github.tieo.arbay.crawler.ErrorType
import io.github.tieo.arbay.crawler.FetchProgressEmitter
import io.github.tieo.arbay.crawler.PlatformStatus
import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.crawler.SoldDetector
import io.github.tieo.arbay.crawler.classifyException
import io.github.tieo.arbay.crawler.trackedSearch
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.repo.ListingRepo
import kotlinx.serialization.Serializable
import io.ktor.http.*
import io.ktor.http.ContentType
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.github.tieo.arbay.crawler.CrawlThrottle
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

@Serializable
private data class CrawlerTestResult(
    val platform: String,
    val query: String,
    val resultCount: Int,
    val status: PlatformStatus,
    val results: List<Listing>,
)

/** Default platforms for general product searches (excludes car/real-estate sites) */
private val GENERAL_PLATFORMS = listOf(
    PlatformId.EBAY_DE, PlatformId.EBAY_COM, PlatformId.KLEINANZEIGEN, PlatformId.AMAZON_DE,
    PlatformId.IDEALO,
    PlatformId.BACKMARKET_DE, PlatformId.REBUY, PlatformId.REFURBED,
    PlatformId.VINTED_DE, PlatformId.WILLHABEN, PlatformId.MARKTPLAATS,
)

/** Reads the optional car-search filters from the request and applies them to a
 *  SearchQuery. Crawlers that support source-side filtering (AutoScout24, Otomoto,
 *  Sauto, DBA, Bytbil, TruckScout24, Bilbasen) turn these into site URL parameters. */
private fun io.ktor.server.routing.RoutingCall.applyCarFilters(base: SearchQuery): SearchQuery {
    val p = queryParameters
    val priceToEur = p["priceTo"]?.toLongOrNull()
    val gear = p["gear"]?.uppercase()?.let {
        when (it) {
            "A", "AUTOMATIC" -> Transmission.AUTOMATIC
            "M", "MANUAL" -> Transmission.MANUAL
            else -> null
        }
    }
    return base.copy(
        firstRegFromYear = p["fregFrom"]?.toIntOrNull(),
        firstRegToYear = p["fregTo"]?.toIntOrNull(),
        maxMileageKm = p["kmTo"]?.toIntOrNull(),
        minPowerKw = p["powerKw"]?.toIntOrNull(),
        maxPrice = priceToEur?.let { Money(it * 100, Currency.EUR) } ?: base.maxPrice,
        transmission = gear,
    )
}

/** Default platforms when the query resolves to a car make/model. Covers Germany
 *  plus cross-border sourcing markets: AutoScout24 spans Western/Central Europe via
 *  its country filter, the national sites reach markets it covers thinly. */
private val CAR_PLATFORMS = listOf(
    PlatformId.AUTOSCOUT24, PlatformId.MOBILE_DE, PlatformId.KLEINANZEIGEN,
    PlatformId.EBAY_DE, PlatformId.TRUCKSCOUT24,
    PlatformId.OTOMOTO, PlatformId.DBA, PlatformId.BILBASEN,
    PlatformId.BYTBIL, PlatformId.SAUTO, PlatformId.MARKTPLAATS,
)

/** Identity a scrape is throttled against: the Authelia-forwarded user when present, else
 *  the client IP. So one account is one bucket regardless of source address. */
private fun RoutingCall.callerKey(): String {
    request.headers["Remote-User"]?.takeIf { it.isNotBlank() }?.let { return "user:$it" }
    val forwarded = request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
    return "ip:" + (forwarded?.takeIf { it.isNotBlank() } ?: request.origin.remoteHost)
}

/** Admits one live scrape or responds 429 with Retry-After. Returns null when rejected
 *  (the caller must stop), else a permit to release in a finally. */
private suspend fun RoutingCall.acquireScrapeSlot(): CrawlThrottle.Result.Permit? =
    when (val decision = CrawlThrottle.tryAcquire(callerKey())) {
        is CrawlThrottle.Result.Permit -> decision
        is CrawlThrottle.Result.Rejected -> {
            response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
            respond(HttpStatusCode.TooManyRequests, mapOf("error" to decision.reason))
            null
        }
    }

fun Route.crawlerRoutes(listingRepo: ListingRepo) {
    route("/api/crawler") {
        get("/platforms") {
            val supported = CrawlerRegistry.supportedPlatforms().map {
                mapOf("id" to it.name, "name" to it.displayName)
            }
            call.respond(supported)
        }

        get("/status") {
            call.respond(CrawlerStatusTracker.getAll())
        }

        get("/exchange-rates") {
            // Refresh if stale (>6h)
            if (System.currentTimeMillis() - ExchangeRates.lastUpdate > 6 * 3600 * 1000) {
                ExchangeRates.refresh()
            }
            call.respond(ExchangeRates.rates)
        }

        get("/config") {
            call.respond(CrawlerConfig.current)
        }
        post("/config") {
            val config = call.receive<CrawlerConfig>()
            CrawlerConfig.update(config)
            call.respond(config)
        }

        get("/status/{platform}") {
            val platformName = call.parameters["platform"] ?: throw BadRequestException("Missing platform")
            val platform = runCatching { PlatformId.valueOf(platformName) }.getOrNull()
                ?: throw BadRequestException("Unknown platform: $platformName")
            call.respond(CrawlerStatusTracker.getStatus(platform))
        }

        // Error snapshots — list, view, resolve
        get("/errors") {
            val resolved = call.queryParameters["resolved"]?.toBooleanStrictOrNull()
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respond(ErrorSnapshotStore.list(resolved = resolved, limit = limit))
        }
        get("/errors/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val snapshot = ErrorSnapshotStore.get(id) ?: throw BadRequestException("Snapshot not found: $id")
            call.respond(snapshot)
        }
        get("/errors/{id}/html") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val html = ErrorSnapshotStore.getHtml(id) ?: throw BadRequestException("HTML not found for: $id")
            call.respondText(html, ContentType.Text.Html)
        }
        post("/errors/{id}/resolve") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            ErrorSnapshotStore.markResolved(id)
            call.respond(mapOf("ok" to true))
        }

        get("/search") {
            val query = call.queryParameters["q"] ?: throw BadRequestException("Missing query parameter 'q'")
            val platformName = call.queryParameters["platform"]
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 50

            val platforms = if (platformName != null) {
                val platform = runCatching { PlatformId.valueOf(platformName) }.getOrNull()
                    ?: throw BadRequestException("Unknown platform: $platformName")
                listOf(platform)
            } else {
                (if (CarQueryResolver.resolve(query) != null) CAR_PLATFORMS else GENERAL_PLATFORMS)
                    .filter { CrawlerRegistry.crawlerFor(it) != null }
            }

            val soldOnly = call.queryParameters["sold"]?.toBooleanStrictOrNull() ?: false
            val searchQuery = call.applyCarFilters(SearchQuery(text = query, soldOnly = soldOnly))

            val permit = call.acquireScrapeSlot() ?: return@get
            val results = try {
                val rawResults = platforms.flatMap { platformId ->
                    val crawler = CrawlerRegistry.crawlerFor(platformId) ?: return@flatMap emptyList()
                    crawler.trackedSearch(searchQuery)
                }
                val filtered = RelevanceFilter.filter(rawResults, searchQuery)
                filtered.map { SoldDetector.classify(it) }
                    .also { it.forEach { l -> listingRepo.upsert(l) } }
                    .sortedBy { it.effectivePrice.amount }.take(limit)
            } finally {
                permit.release()
            }

            call.respond(results)
        }

        // Streaming search — returns newline-delimited JSON events
        get("/search/stream") {
            val query = call.queryParameters["q"] ?: throw BadRequestException("Missing query parameter 'q'")
            val platformName = call.queryParameters["platform"]
            val platformNames = call.queryParameters["platforms"]

            val platforms = if (platformName != null) {
                val platform = runCatching { PlatformId.valueOf(platformName) }.getOrNull()
                    ?: throw BadRequestException("Unknown platform: $platformName")
                listOf(platform)
            } else if (platformNames != null) {
                platformNames.split(",").mapNotNull { runCatching { PlatformId.valueOf(it.trim()) }.getOrNull() }
            } else {
                // Default: general product platforms (exclude car/house sites)
                (if (CarQueryResolver.resolve(query) != null) CAR_PLATFORMS else GENERAL_PLATFORMS)
                    .filter { CrawlerRegistry.crawlerFor(it) != null }
            }

            val searchQuery = call.applyCarFilters(SearchQuery(text = query))
            val parsedQuery = RelevanceFilter.parseQuery(query)

            val permit = call.acquireScrapeSlot() ?: return@get
            try {
            call.respondTextWriter(contentType = ContentType.Text.Plain) {
                // Send SEARCH_STARTED
                val startEvent = CrawlerSearchEvent(
                    type = CrawlerEventType.SEARCH_STARTED,
                    platform = "",
                    totalPlatforms = platforms.size,
                )
                write(json.encodeToString(startEvent) + "\n")
                flush()

                // Run crawlers in parallel, stream results as they complete
                coroutineScope {
                    // Keepalive: send a blank line every 5s so the client TCP connection stays alive
                    // (without this, Android drops idle connections after ~30s)
                    val keepalive = launch {
                        while (true) {
                            delay(5_000L)
                            try { synchronized(this@respondTextWriter) { write("\n"); flush() } }
                            catch (_: Exception) { break }
                        }
                    }

                    val resultChannel = Channel<CrawlerSearchEvent>(Channel.UNLIMITED)
                    val jobs = platforms.map { platformId ->
                        launch {
                            val crawler = CrawlerRegistry.crawlerFor(platformId)
                            if (crawler == null) {
                                resultChannel.send(CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_ERROR,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    error = "No crawler available",
                                ))
                                return@launch
                            }

                            // Send PLATFORM_STARTED
                            val startedEvent = CrawlerSearchEvent(
                                type = CrawlerEventType.PLATFORM_STARTED,
                                platform = platformId.name,
                                platformName = platformId.displayName,
                            )
                            synchronized(this@respondTextWriter) {
                                write(json.encodeToString(startedEvent) + "\n")
                                flush()
                            }

                            val progressEmitter = FetchProgressEmitter { stage ->
                                resultChannel.send(CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_PROGRESS,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    fetchStage = stage,
                                ))
                            }

                            val event = try {
                                val rawResults = withTimeout(300_000L) {
                                    kotlinx.coroutines.withContext(progressEmitter) { crawler.search(searchQuery) }
                                }
                                val irrelevance = RelevanceFilter.irrelevanceReport(rawResults, searchQuery)
                                if (irrelevance != null) {
                                    CrawlerStatusTracker.recordError(platformId, irrelevance, ErrorType.IRRELEVANT_RESULTS)
                                } else {
                                    CrawlerStatusTracker.recordSuccess(platformId, rawResults.size)
                                }
                                val relevantResults = RelevanceFilter.filter(rawResults, searchQuery)
                                val results = relevantResults.map { SoldDetector.classify(it) }
                                results.forEach { listingRepo.upsert(it) }

                                CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_DONE,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    resultCount = results.size,
                                    rawCount = rawResults.size,
                                    listings = results,
                                )
                            } catch (e: TimeoutCancellationException) {
                                CrawlerStatusTracker.recordError(platformId, "Timeout after 180s", ErrorType.TIMEOUT)
                                val snapId = try {
                                    ErrorSnapshotStore.capture(
                                        platform = platformId.name, query = query, error = RuntimeException("Timeout after 180s", e), errorType = ErrorType.TIMEOUT,
                                    )
                                } catch (_: Exception) { "?" }
                                CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_ERROR,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    error = "Timeout after 180s [$snapId]",
                                    errorType = "TIMEOUT",
                                )
                            } catch (e: CrawlerBlockedException) {
                                CrawlerStatusTracker.recordError(platformId, e.message ?: "Blocked", e.errorType)
                                val snapId = ErrorSnapshotStore.capture(
                                    platform = platformId.name, query = query, error = e, errorType = e.errorType,
                                )
                                CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_ERROR,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    error = "${e.message ?: "Blocked"} [$snapId]",
                                    errorType = e.errorType.name,
                                    captchaUrl = if (e.errorType == ErrorType.CAPTCHA) "https://${platformId.displayName.lowercase().replace(" ", "")}.de" else null,
                                )
                            } catch (e: Exception) {
                                val errorType = classifyException(e)
                                CrawlerStatusTracker.recordError(platformId, e.message ?: "Unknown error", errorType)
                                val snapId = ErrorSnapshotStore.capture(
                                    platform = platformId.name, query = query, error = e, errorType = errorType,
                                )
                                CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_ERROR,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    error = "${e.message ?: "Unknown error"} [$snapId]",
                                    errorType = errorType.name,
                                )
                            }
                            resultChannel.send(event)
                        }
                    }
                    // Close channel once all platform coroutines finish
                    launch { jobs.joinAll(); resultChannel.close() }

                    var completed = 0
                    for (event in resultChannel) {
                        val isProgress = event.type == CrawlerEventType.PLATFORM_PROGRESS
                        if (!isProgress) completed++
                        val eventToWrite = if (isProgress) event else event.copy(
                            completedPlatforms = completed,
                            totalPlatforms = platforms.size,
                        )
                        synchronized(this@respondTextWriter) {
                            write(json.encodeToString(eventToWrite) + "\n")
                            flush()
                        }
                    }
                    keepalive.cancel()
                }

                // Send SEARCH_COMPLETE
                val completeEvent = CrawlerSearchEvent(
                    type = CrawlerEventType.SEARCH_COMPLETE,
                    platform = "",
                    completedPlatforms = platforms.size,
                    totalPlatforms = platforms.size,
                )
                write(json.encodeToString(completeEvent) + "\n")
                flush()
            }
            } finally {
                permit.release()
            }
        }

        get("/test/{platform}") {
            val platformName = call.parameters["platform"] ?: throw BadRequestException("Missing platform")
            val platform = runCatching { PlatformId.valueOf(platformName) }.getOrNull()
                ?: throw BadRequestException("Unknown platform: $platformName")
            val query = call.queryParameters["q"] ?: "Sony WH-1000XM4"

            val crawler = CrawlerRegistry.crawlerFor(platform)
                ?: throw BadRequestException("No crawler for $platformName")

            val searchQuery = call.applyCarFilters(SearchQuery(text = query))
            val permit = call.acquireScrapeSlot() ?: return@get
            val results = try { crawler.trackedSearch(searchQuery) } finally { permit.release() }

            val status = CrawlerStatusTracker.getStatus(platform)
            call.respond(CrawlerTestResult(
                platform = platformName,
                query = query,
                resultCount = results.size,
                status = status,
                results = results.take(5),
            ))
        }
    }
}
