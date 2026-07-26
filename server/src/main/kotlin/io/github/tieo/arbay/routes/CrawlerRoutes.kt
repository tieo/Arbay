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
import io.github.tieo.arbay.classifier.CarCriteriaScorer
import io.github.tieo.arbay.crawler.CaptchaInteractiveEmitter
import io.github.tieo.arbay.crawler.Geocoder
import io.github.tieo.arbay.crawler.FetchProgressEmitter
import io.github.tieo.arbay.crawler.PartialResultEmitter
import io.github.tieo.arbay.crawler.PlatformStatus
import io.github.tieo.arbay.crawler.QueryResultCache
import io.github.tieo.arbay.crawler.CarFilterEngine
import io.github.tieo.arbay.crawler.DetailEnricher
import io.github.tieo.arbay.crawler.RequestMonitor
import io.github.tieo.arbay.crawler.Translator
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.toCarFilters
import io.github.tieo.arbay.crawler.QueryVariants
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
    // Cross-border sourcing: the query is translated into each site's language (localizedQuery)
    // before searching, so a German product term reaches Italian/French/Spanish/Dutch listings.
    PlatformId.EBAY_IT, PlatformId.EBAY_FR, PlatformId.EBAY_ES, PlatformId.TWEEDEHANDS,
    // Switzerland's largest general marketplace; German-language, so the query needs no translation.
    PlatformId.RICARDO, PlatformId.SUBITO,
)

/** Whether a query names a vehicle, which decides both the markets searched and whether the car
 *  post-filter runs. Resolved once per request and passed on, not re-derived at each use. */
private fun isCarQuery(query: String): Boolean = CarQueryResolver.resolve(query) != null

/** The markets a query is searched on when the request names none: the vehicle sites for a car,
 *  the general marketplaces otherwise, minus any without a crawler. */
private fun defaultPlatforms(isCar: Boolean): List<PlatformId> =
    (if (isCar) CAR_PLATFORMS else GENERAL_PLATFORMS).filter { CrawlerRegistry.crawlerFor(it) != null }

/** A platform's finished result set: the car post-filter over the raw relevance-filtered crawl,
 *  with every listing measured against the searcher, plus the facet counts. The same step whether
 *  the crawl was fresh or served from cache. */
private suspend fun finishedResults(
    raw: List<Listing>,
    query: SearchQuery,
    isCarQuery: Boolean,
    crawler: Crawler,
    listingRepo: ListingRepo,
): Pair<List<Listing>, Map<String, Int>> {
    val listings = annotateDistance(carPostFilter(raw, query, isCarQuery, crawler), query)
    listings.forEach { listingRepo.upsert(it) }
    val facets = if (isCarQuery)
        CarFilterEngine.facetCounts(raw, query.toCarFilters() ?: CarFilters()) else emptyMap()
    return listings to facets
}

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
    // Fold the per-field parameters into the same filter set, so a request arrives as one thing
    // whichever app version sent it and crawlers read their native parameters from one place.
    val filters = (cf ?: CarFilters()).let { f ->
        f.copy(
            firstRegFromYear = f.firstRegFromYear ?: p["fregFrom"]?.toIntOrNull(),
            firstRegToYear = f.firstRegToYear ?: p["fregTo"]?.toIntOrNull(),
            maxMileageKm = f.maxMileageKm ?: p["kmTo"]?.toIntOrNull(),
            minPowerKw = f.minPowerKw ?: p["powerKw"]?.toIntOrNull(),
            minPriceEur = f.minPriceEur ?: base.minPrice?.let { (it.amount / 100).toInt() },
            maxPriceEur = f.maxPriceEur ?: priceToEur?.toInt()
                ?: base.maxPrice?.let { (it.amount / 100).toInt() },
            transmission = f.transmission ?: legacyGear,
            descriptionContains = f.descriptionContains ?: p["inDescription"]?.takeIf { it.isNotBlank() },
        )
    }
    return base.withCarFilters(filters.takeUnless { it.isEmpty }).copy(
        userLat = p["lat"]?.toDoubleOrNull(),
        userLon = p["lon"]?.toDoubleOrNull(),
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
    // "345.000 km" becomes a value the mileage filter can exclude on.
    // A query that is itself after a part ("Crafter Drehkonsole") must not have the non-vehicle
    // guard strip those parts out; then it returns the parts the user asked for.
    val partsIntent = CarFilterEngine.isPartQuery(searchQuery.positiveText)
    val enriched = listings.map { VehicleTextParser.enrich(it) }
    val cardFiltered = CarFilterEngine.apply(enriched, filters, keepNonVehicles = partsIntent)
    val detailed = DetailEnricher.enrich(cardFiltered, filters, crawler)
    val filtered = CarFilterEngine.apply(detailed, filters, keepNonVehicles = partsIntent)
    // A free-form ideal-car description ranks (not filters) the survivors by local semantic
    // similarity, so the best matches surface first. Skipped when none was given.
    return filters.idealDescription?.takeIf { it.isNotBlank() }
        ?.let { CarCriteriaScorer.rank(filtered, it) } ?: filtered
}

/** Resolve every listing's location (zip/city + country) to coordinates, and fill in distanceKm
 *  when the search carries the searcher's position. Geocoding runs unconditionally so the client
 *  can measure distances itself later — picking "nearest first" then costs no crawl. A listing
 *  with no resolvable location is left as it is. */
private fun annotateDistance(listings: List<Listing>, query: SearchQuery): List<Listing> {
    val lat = query.userLat
    val lon = query.userLon
    return listings.map { l ->
        val loc = l.location ?: return@map l
        val coords = if (loc.latitude != null && loc.longitude != null) loc.latitude!! to loc.longitude!!
            else Geocoder.resolve(loc.country, loc.zip, loc.city) ?: return@map l
        l.copy(
            location = loc.copy(latitude = coords.first, longitude = coords.second),
            distanceKm = if (lat != null && lon != null)
                Geocoder.haversine(lat, lon, coords.first, coords.second) else l.distanceKm,
        )
    }
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
    PlatformId.AUTOSCOUT24_BE, PlatformId.AUTOVIT, PlatformId.RICARDO, PlatformId.SUBITO, PlatformId.TWEEDEHANDS, PlatformId.AUTOPLIUS, PlatformId.NETTIAUTO, PlatformId.FINN, PlatformId.OLX_PT, PlatformId.KUPUJEM,
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
                defaultPlatforms(isCarQuery(query))
            }

            val soldOnly = call.queryParameters["sold"]?.toBooleanStrictOrNull() ?: false
            val searchQuery = call.applyCarFilters(SearchQuery(text = query, soldOnly = soldOnly))

            val permit = call.acquireScrapeSlot() ?: return@get
            val results = try {
                val isCarQuery = isCarQuery(query)
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
                    val result = annotateDistance(carPostFilter(classified, pq, isCarQuery, crawler), pq)
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
                defaultPlatforms(isCarQuery(query))
            }

            val searchQuery = call.applyCarFilters(SearchQuery(text = query))
            val parsedQuery = RelevanceFilter.parseQuery(query)
            val isCarQuery = isCarQuery(query)

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

                            // Cross-border markets are searched in their own language; surface the
                            // translated term so the user sees what each foreign site was queried with.
                            val pq = localizedQuery(searchQuery, platformId)

                            // Send PLATFORM_STARTED
                            val startedEvent = CrawlerSearchEvent(
                                type = CrawlerEventType.PLATFORM_STARTED,
                                platform = platformId.name,
                                platformName = platformId.displayName,
                                queryUsed = pq.text.takeIf { it != searchQuery.text },
                            )
                            synchronized(this@respondTextWriter) {
                                write(json.encodeToString(startedEvent) + "\n")
                                flush()
                            }

                            // Fresh cached crawl for this exact query skips the crawl entirely, so
                            // re-running a search (e.g. after a filter tweak) fires no requests.
                            // Car post-filtering still runs on the cached set so the new filters apply.
                            val cached = QueryResultCache.get(platformId, pq)
                            if (cached != null) {
                                val (filtered, facets) =
                                    finishedResults(cached, pq, isCarQuery, crawler, listingRepo)
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

                            // Pipeline: as the crawler parses each page it streams the batch here,
                            // which card-filters it (relevance + sold + car card filter, no detail
                            // enrichment — that runs once at the end) and pushes it to the client so
                            // early pages render while later pages and platforms keep fetching. The
                            // final PLATFORM_DONE carries the authoritative detail-enriched set that
                            // the client reconciles against, so preview items filtered out later drop.
                            val emittedIds = java.util.Collections.synchronizedSet(HashSet<String>())
                            val partialFilters = pq.toCarFilters() ?: CarFilters()
                            val partsIntent = CarFilterEngine.isPartQuery(pq.positiveText)
                            val partialEmitter = PartialResultEmitter { pageListings ->
                                val relevant = RelevanceFilter.filter(pageListings, pq)
                                val classified = relevant.map { SoldDetector.classify(it) }
                                val card = if (isCarQuery)
                                    CarFilterEngine.apply(classified.map { VehicleTextParser.enrich(it) }, partialFilters, keepNonVehicles = partsIntent)
                                else classified
                                val fresh = annotateDistance(card.filter { emittedIds.add(it.id) }, pq)
                                if (fresh.isNotEmpty()) {
                                    fresh.forEach { listingRepo.upsert(it) }
                                    resultChannel.send(CrawlerSearchEvent(
                                        type = CrawlerEventType.PLATFORM_PROGRESS,
                                        platform = platformId.name,
                                        platformName = platformId.displayName,
                                        listings = fresh,
                                        resultCount = fresh.size,
                                    ))
                                }
                            }

                            // When a stealth crawl exposes its live browser for a human captcha
                            // solve, push the noVNC path to the client. It rides the same Authelia-
                            // gated arbay host (Caddy /captcha/ → the container's noVNC), so the app
                            // prepends its own base URL.
                            val captchaEmitter = CaptchaInteractiveEmitter {
                                resultChannel.send(CrawlerSearchEvent(
                                    type = CrawlerEventType.CAPTCHA_INTERACTIVE,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    captchaUrl = "/captcha/vnc.html?autoconnect=true&resize=scale",
                                ))
                            }

                            val event = try {
                                val rawResults = withTimeout(300_000L) {
                                    kotlinx.coroutines.withContext(progressEmitter + partialEmitter + captchaEmitter) {
                                        // A site matches the query as a literal word, so a compound
                                        // noun is also searched under its interchangeable spellings
                                        // ("Parkettschleifer" for "Parkettschleifmaschine"). The
                                        // query itself decides the platform's health; a variant that
                                        // fails is simply dropped.
                                        val primary = crawler.search(pq)
                                        val extra = QueryVariants.of(pq.text).flatMap { v ->
                                            runCatching { crawler.search(pq.copy(text = v)) }.getOrDefault(emptyList())
                                        }
                                        (primary + extra).distinctBy { it.id }
                                    }
                                }
                                // A crawler that does not stream per page (single-fetch, or one not
                                // yet wired) still surfaces its whole parsed set here, before the
                                // detail-enrichment step below adds latency. Already-streamed pages
                                // are deduped inside the emitter, so a per-page crawler emits nothing
                                // extra.
                                partialEmitter.emit(rawResults)
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
                                val (results, facets) =
                                    finishedResults(classified, pq, isCarQuery, crawler, listingRepo)

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
