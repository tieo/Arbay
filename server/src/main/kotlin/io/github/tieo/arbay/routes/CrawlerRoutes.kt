package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.CrawlerBlockedException
import io.github.tieo.arbay.crawler.CrawlerConfig
import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.CrawlerStatusTracker
import io.github.tieo.arbay.crawler.ErrorSnapshotStore
import io.github.tieo.arbay.crawler.ExchangeRates
import io.github.tieo.arbay.crawler.ErrorType
import io.github.tieo.arbay.crawler.FetchProgressEmitter
import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.crawler.SoldDetector
import io.github.tieo.arbay.crawler.classifyException
import io.github.tieo.arbay.crawler.trackedSearch
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.repo.ListingRepo
import io.ktor.http.*
import io.ktor.http.ContentType
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
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

/** Default platforms for general product searches (excludes car/real-estate sites) */
private val GENERAL_PLATFORMS = listOf(
    PlatformId.EBAY_DE, PlatformId.EBAY_COM, PlatformId.KLEINANZEIGEN, PlatformId.AMAZON_DE,
    PlatformId.IDEALO,
    PlatformId.BACKMARKET_DE, PlatformId.REBUY, PlatformId.REFURBED,
    PlatformId.VINTED_DE, PlatformId.WILLHABEN, PlatformId.MARKTPLAATS,
)

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
                GENERAL_PLATFORMS.filter { CrawlerRegistry.crawlerFor(it) != null }
            }

            val soldOnly = call.queryParameters["sold"]?.toBooleanStrictOrNull() ?: false
            val searchQuery = SearchQuery(text = query, soldOnly = soldOnly)
            val rawResults = platforms.flatMap { platformId ->
                val crawler = CrawlerRegistry.crawlerFor(platformId) ?: return@flatMap emptyList()
                crawler.trackedSearch(searchQuery)
            }
            val filtered = RelevanceFilter.filter(rawResults, searchQuery)
            val results = filtered.map { SoldDetector.classify(it) }
                .also { it.forEach { l -> listingRepo.upsert(l) } }
                .sortedBy { it.effectivePrice.amount }.take(limit)

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
                GENERAL_PLATFORMS.filter { CrawlerRegistry.crawlerFor(it) != null }
            }

            // Blocked terms from client — append as negative keywords
            val blockedTerms = call.queryParameters["blocked"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val queryWithBlocked = if (blockedTerms.isNotEmpty()) {
                "$query ${blockedTerms.joinToString(" ") { "-$it" }}"
            } else query
            val searchQuery = SearchQuery(text = queryWithBlocked)
            val parsedQuery = RelevanceFilter.parseQuery(queryWithBlocked)

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
                                val rawResults = withTimeout(180_000L) {
                                    kotlinx.coroutines.withContext(progressEmitter) { crawler.search(searchQuery) }
                                }
                                CrawlerStatusTracker.recordSuccess(platformId, rawResults.size)
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
        }

        get("/test/{platform}") {
            val platformName = call.parameters["platform"] ?: throw BadRequestException("Missing platform")
            val platform = runCatching { PlatformId.valueOf(platformName) }.getOrNull()
                ?: throw BadRequestException("Unknown platform: $platformName")
            val query = call.queryParameters["q"] ?: "Sony WH-1000XM4"

            val crawler = CrawlerRegistry.crawlerFor(platform)
                ?: throw BadRequestException("No crawler for $platformName")

            val searchQuery = SearchQuery(text = query)
            val results = crawler.trackedSearch(searchQuery)

            val status = CrawlerStatusTracker.getStatus(platform)
            call.respond(
                mapOf(
                    "platform" to platformName,
                    "query" to query,
                    "resultCount" to results.size,
                    "status" to status,
                    "results" to results.take(5),
                ),
            )
        }
    }
}
