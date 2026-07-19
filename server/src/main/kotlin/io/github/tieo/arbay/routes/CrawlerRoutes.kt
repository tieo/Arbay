package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.BlockCooldown
import io.github.tieo.arbay.crawler.CarQueryResolver
import io.github.tieo.arbay.crawler.Crawler
import io.github.tieo.arbay.crawler.CrawlerBlockedException
import io.github.tieo.arbay.crawler.CrawlerConfig
import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.CrawlerStatusTracker
import io.github.tieo.arbay.crawler.VehicleTextParser
import io.github.tieo.arbay.crawler.ErrorSnapshotStore
import io.github.tieo.arbay.crawler.ExchangeRates
import io.github.tieo.arbay.crawler.ErrorType
import io.github.tieo.arbay.crawler.FetchProgressEmitter
import io.github.tieo.arbay.crawler.PlatformStatus
import io.github.tieo.arbay.crawler.QueryResultCache
import io.github.tieo.arbay.crawler.CarFilterEngine
import io.github.tieo.arbay.crawler.DetailEnricher
import io.github.tieo.arbay.crawler.RequestMonitor
import io.github.tieo.arbay.crawler.Translator
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.toCarFilters
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
    // Full filter set as one JSON param (handles multi-selects); legacy per-field params below
    // are the fallback for older app versions.
    val cf = p["carFilters"]?.let { runCatching { json.decodeFromString<CarFilters>(it) }.getOrNull() }
    val priceToEur = p["priceTo"]?.toLongOrNull()
    val legacyGear = p["gear"]?.uppercase()?.let {
        when (it) {
            "A", "AUTOMATIC" -> Transmission.AUTOMATIC
            "M", "MANUAL" -> Transmission.MANUAL
            else -> null
        }
    }
    // Mirror the fields crawlers turn into native URL params (year/mileage/price/power/gearbox);
    // the rest of cf is enforced by post-filtering.
    return base.copy(
        carFilters = cf,
        firstRegFromYear = cf?.firstRegFromYear ?: p["fregFrom"]?.toIntOrNull(),
        firstRegToYear = cf?.firstRegToYear ?: p["fregTo"]?.toIntOrNull(),
        maxMileageKm = cf?.maxMileageKm ?: p["kmTo"]?.toIntOrNull(),
        minPowerKw = cf?.minPowerKw ?: p["powerKw"]?.toIntOrNull(),
        maxPrice = cf?.maxPriceEur?.let { Money(it * 100L, Currency.EUR) }
            ?: priceToEur?.let { Money(it * 100, Currency.EUR) } ?: base.maxPrice,
        transmission = cf?.transmission ?: legacyGear,
        descriptionContains = cf?.descriptionContains ?: p["inDescription"]?.takeIf { it.isNotBlank() },
    )
}

/** The search query localized to a platform's language — a cross-border market (e.g. eBay.it) is
 *  searched with the translated term ("Parkettschleifmaschine" → "levigatrice per parquet") so its
 *  own search returns local listings; the home language passes through unchanged. Both the crawl
 *  and the relevance filter then use this same localized query. */
private suspend fun localizedQuery(base: SearchQuery, platform: PlatformId): SearchQuery {
    if (platform.searchLanguage == "de" || base.text.isBlank()) return base
    val translated = Translator.translate(base.text, "de", platform.searchLanguage)
    return if (translated == base.text) base else base.copy(text = translated)
}

/** A price in EUR cents, converting from the listing's own currency so cross-border results
 *  (PLN/SEK/DKK/…) rank by real value instead of raw amount. */
private fun priceEurCents(money: Money): Long =
    if (money.currency == Currency.EUR) money.amount
    else ExchangeRates.convert(money.amount, money.currency.name, "EUR")

/** The car post-filter pipeline, run AFTER the crawl cache so a filter tweak re-filters cached
 *  listings instead of re-crawling: card-level filter → detail-verify the survivors → final
 *  filter. Specs come from structured sources only (card + detail table), never free-text
 *  guessing. Non-car queries pass through unchanged. */
private suspend fun carPostFilter(
    listings: List<Listing>,
    searchQuery: SearchQuery,
    isCarQuery: Boolean,
    crawler: Crawler,
): List<Listing> {
    if (!isCarQuery) return listings
    val filters = searchQuery.toCarFilters() ?: CarFilters()
    // Parse specs from each card's own title/description first (mileage, year, power, …), so a
    // platform that ships no structured data — eBay, Kleinanzeigen — is still filterable: a stated
    // "345.000 km" becomes a value the mileage filter can exclude on (when useTextSpecs is set).
    val enriched = listings.map { VehicleTextParser.enrich(it) }
    val cardFiltered = CarFilterEngine.apply(enriched, filters)
    val detailed = DetailEnricher.enrich(cardFiltered, filters, crawler)
    return CarFilterEngine.apply(detailed, filters)
}

/** Default platforms when the query resolves to a car make/model. Covers Germany
 *  plus cross-border sourcing markets: AutoScout24 spans Western/Central Europe via
 *  its country filter, the national sites reach markets it covers thinly. */
private val CAR_PLATFORMS = listOf(
    PlatformId.AUTOSCOUT24, PlatformId.MOBILE_DE, PlatformId.KLEINANZEIGEN,
    PlatformId.EBAY_DE, PlatformId.TRUCKSCOUT24,
    PlatformId.OTOMOTO, PlatformId.DBA, PlatformId.BILBASEN,
    PlatformId.BYTBIL, PlatformId.SAUTO, PlatformId.MARKTPLAATS,
    PlatformId.WILLHABEN,
    PlatformId.AUTOSCOUT24_IT, PlatformId.AUTOSCOUT24_FR, PlatformId.AUTOSCOUT24_ES,
    PlatformId.AUTOSCOUT24_BE, PlatformId.AUTOVIT, PlatformId.RICARDO, PlatformId.SUBITO,
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

        // Outbound request telemetry: counts, block rate, fetch tier used per platform.
        get("/request-stats") {
            call.respond(RequestMonitor.getAll())
        }

        // Tail the server log (last N lines, newest last) — inspect crawls/blocks/errors from
        // the app or a browser without SSH. Behind Authelia like everything else.
        get("/logs") {
            val n = (call.queryParameters["lines"]?.toIntOrNull() ?: 300).coerceIn(1, 5000)
            val logFile = java.io.File(System.getProperty("user.home"), ".arbay/logs/arbay.log")
            val text = if (logFile.exists())
                logFile.readLines().takeLast(n).joinToString("\n")
            else "no log file at ${logFile.path}"
            call.respondText(text, ContentType.Text.Plain)
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
                val isCarQuery = CarQueryResolver.resolve(query) != null
                val perPlatform = platforms.map { platformId ->
                    val crawler = CrawlerRegistry.crawlerFor(platformId) ?: return@map emptyList()
                    // Cross-border markets are searched in their own language.
                    val pq = localizedQuery(searchQuery, platformId)
                    // Cache holds the raw relevance-filtered crawl; car post-filtering runs after
                    // it, so tweaking a filter re-filters cached listings instead of re-crawling.
                    // Skip the crawl (serve cache only) while the platform is cooling down from a
                    // recent block — hitting it again would deepen the block.
                    val classified = QueryResultCache.get(platformId, pq)
                        ?: if (BlockCooldown.isCoolingDown(platformId)) emptyList()
                        else run {
                        val raw = crawler.trackedSearch(pq)
                        val filtered = RelevanceFilter.filter(raw, pq).map { SoldDetector.classify(it) }
                        QueryResultCache.put(platformId, pq, filtered)
                        filtered
                    }
                    val result = carPostFilter(classified, pq, isCarQuery, crawler)
                    result.forEach { listingRepo.upsert(it) }
                    result
                }
                // Sort by EUR-normalized price so cross-currency listings (PLN/SEK/…) rank by real
                // value, not raw amount; dedup by id before taking the cheapest N.
                perPlatform.flatten()
                    .distinctBy { it.id }
                    .sortedBy { priceEurCents(it.effectivePrice) }
                    .take(limit)
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
            val isCarQuery = CarQueryResolver.resolve(query) != null

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

                            // Cross-border markets are searched in their own language.
                            val pq = localizedQuery(searchQuery, platformId)

                            // Fresh cached crawl for this exact query skips the crawl entirely, so
                            // re-running a search (e.g. after a filter tweak) fires no requests.
                            // Car post-filtering still runs on the cached set so the new filters apply.
                            val cached = QueryResultCache.get(platformId, pq)
                            if (cached != null) {
                                val filtered = carPostFilter(cached, pq, isCarQuery, crawler)
                                filtered.forEach { listingRepo.upsert(it) }
                                val facets = if (isCarQuery)
                                    CarFilterEngine.facetCounts(cached, pq.toCarFilters() ?: CarFilters())
                                else emptyMap()
                                resultChannel.send(CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_DONE,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    resultCount = filtered.size,
                                    rawCount = cached.size,
                                    listings = filtered,
                                    fromCache = true,
                                    facets = facets,
                                ))
                                return@launch
                            }

                            // Cooling down from a recent block: don't re-crawl (would deepen the
                            // block). Report it so the user sees "cooling down", not a silent 0.
                            val coolMs = BlockCooldown.remainingMs(platformId)
                            if (coolMs > 0) {
                                resultChannel.send(CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_ERROR,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    error = "Cooling down after a block (~${coolMs / 60000} min left)",
                                    errorType = "COOLING_DOWN",
                                ))
                                return@launch
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
                                    kotlinx.coroutines.withContext(progressEmitter) { crawler.search(pq) }
                                }
                                val irrelevance = RelevanceFilter.irrelevanceReport(rawResults, pq)
                                if (irrelevance != null) {
                                    CrawlerStatusTracker.recordError(platformId, irrelevance, ErrorType.IRRELEVANT_RESULTS)
                                } else {
                                    CrawlerStatusTracker.recordSuccess(platformId, rawResults.size)
                                }
                                val relevantResults = RelevanceFilter.filter(rawResults, pq)
                                val classified = relevantResults.map { SoldDetector.classify(it) }
                                // Cache the raw relevance-filtered crawl; car post-filtering (card
                                // filter → detail-verify → final filter) runs after, so a later
                                // filter tweak re-filters from cache without re-crawling.
                                QueryResultCache.put(platformId, pq, classified)
                                val results = carPostFilter(classified, pq, isCarQuery, crawler)
                                results.forEach { listingRepo.upsert(it) }
                                val facets = if (isCarQuery)
                                    CarFilterEngine.facetCounts(classified, pq.toCarFilters() ?: CarFilters())
                                else emptyMap()

                                CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_DONE,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    resultCount = results.size,
                                    rawCount = rawResults.size,
                                    listings = results,
                                    facets = facets,
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
                                if (BlockCooldown.isBlock(e.errorType)) BlockCooldown.record(platformId)
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
                                // Client disconnected mid-search (a cancelling parent scope) — not a
                                // crawler failure; don't record it or emit an error event.
                                if (e.message?.contains("Cancelling") == true || e.message?.contains("Cancelled") == true) {
                                    return@launch
                                }
                                val errorType = classifyException(e)
                                CrawlerStatusTracker.recordError(platformId, e.message ?: "Unknown error", errorType)
                                if (BlockCooldown.isBlock(errorType)) BlockCooldown.record(platformId)
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
