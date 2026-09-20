package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.BlockCooldown
import io.github.tieo.arbay.crawler.CarQueryResolver
import io.github.tieo.arbay.crawler.Crawler
import io.github.tieo.arbay.crawler.CrawlerBlockedException
import io.github.tieo.arbay.crawler.CrawlerConfig
import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.crawler.SellsByAuction
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
import io.github.tieo.arbay.crawler.area
import io.github.tieo.arbay.crawler.askedInItsOwnLanguage
import io.github.tieo.arbay.crawler.localizedQuery
import io.github.tieo.arbay.crawler.TermVerdictEmitter
import io.github.tieo.arbay.crawler.TermsUsedEmitter
import io.github.tieo.arbay.crawler.Translator
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.toCarFilters
import io.github.tieo.arbay.crawler.QueryVariants
import io.github.tieo.arbay.crawler.searchAllSpellings
import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.crawler.SoldDetector
import io.github.tieo.arbay.crawler.classifyException
import io.github.tieo.arbay.crawler.trackedSearch
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.MarketSettingsStore
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// The app can be a version ahead of the server; a filter field this build does not
// know must not cost the reader every other filter they set.
private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@Serializable
private data class CrawlerTestResult(
    val platform: String,
    val query: String,
    val resultCount: Int,
    val status: PlatformStatus,
    val results: List<Listing>,
)

/** Default platforms for general product searches (excludes car/real-estate sites). MarketSets is
 *  shared with the client so a typed custom search defaults the same way, not to every crawlable
 *  platform. */
private val GENERAL_PLATFORMS = MarketSets.general

/** How common a word is across every listing crawled so far, so a search's own results can be
 *  judged against it when looking for the market's other names for the thing. */
private fun corpusBackground(listingRepo: ListingRepo) =
    QueryVariants.TermBackground { term -> listingRepo.titleShareOfCorpus(term) }

/** Whether a query names a vehicle, which decides both the markets searched and whether the car
 *  post-filter runs. Resolved once per request and passed on, not re-derived at each use. */
private fun isCarQuery(query: String): Boolean = CarQueryResolver.resolve(query) != null

/** The markets a query is searched on when the request names none: the vehicle sites for a car,
 *  the general marketplaces otherwise, minus any without a crawler. */
private fun defaultPlatforms(isCar: Boolean): List<PlatformId> {
    val group = if (isCar) MarketGroup.VEHICLES else MarketGroup.GENERAL
    val countries = MarketSettingsStore.current.countries
    val inCountries = MarketSets.platformsIn(group, countries)
        .filter { CrawlerRegistry.crawlerFor(it) != null }
    // A country set nothing can be crawled in would silently search nothing at all, which reads as
    // every market failing. The full set answers instead, and the markets view names the countries.
    return inCountries.ifEmpty {
        (if (isCar) CAR_PLATFORMS else GENERAL_PLATFORMS).filter { CrawlerRegistry.crawlerFor(it) != null }
    }
}

/** A platform's finished result set: what is shown, what fell outside the search's area, and the
 *  facet counts. The same step whether the crawl was fresh or served from cache. */
private data class FinishedResults(
    val listings: List<Listing>,
    val tooFar: List<Listing>,
    /** What the vehicle criteria removed, each naming the criterion that removed it. */
    val criteriaDropped: List<DroppedListing>,
    val facets: Map<String, Int>,
)

/** The car post-filter over the raw relevance-filtered crawl, then the search's area, with every
 *  listing measured against the searcher. */
private suspend fun finishedResults(
    raw: List<Listing>,
    query: SearchQuery,
    isCarQuery: Boolean,
    crawler: Crawler,
    listingRepo: ListingRepo,
): FinishedResults {
    // A market that publishes no location on its cards is asked for one per listing, but only
    // where a location decides something: a search centred on a place measures every listing
    // against it, and one with no location cannot be measured at all. This runs before the area is
    // applied, so a listing eBay never placed is judged on what its own page says rather than
    // waved through for want of an address.
    val (filtered, criteriaDropped) = carPostFilter(raw, query, isCarQuery, crawler)
    val placed = if (query.area(io.github.tieo.arbay.repo.ImportSettingsStore.current.homeCountry) != null)
        io.github.tieo.arbay.crawler.LocationEnricher.enrich(filtered, crawler) else filtered
    val (inside, outside) = outsideTheArea(placed, query)
    val listings = asDelivered(inside, query)
    listings.forEach { listingRepo.upsert(it) }
    val facets = if (isCarQuery)
        CarFilterEngine.facetCounts(raw, query.toCarFilters() ?: CarFilters()) else emptyMap()
    return FinishedResults(listings, asDelivered(outside, query), criteriaDropped, facets)
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

/** Structured search-text extras carried as request params, never folded into the query text
 *  itself — an alternate phrasing or an excluded word is data about the search, not something
 *  typed into the box a person or a catalog entry's [io.github.tieo.arbay.catalog.KnownProduct]
 *  names their search by. */
private fun io.ktor.server.routing.RoutingCall.applySearchExtras(base: SearchQuery): SearchQuery {
    val excludeKeywords = queryParameters["excludeKeywords"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    val aliases = queryParameters["aliases"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    // How far this search may travel from the typed words, sent per search rather than configured
    // once on the server: what one search is allowed to ask a market is not a property of the box
    // the words were typed into.
    val reach = queryParameters["reach"]
        ?.let { runCatching { json.decodeFromString<SearchReach>(it) }.getOrNull() }
    return base.copy(
        excludeKeywords = excludeKeywords ?: base.excludeKeywords,
        aliases = aliases ?: base.aliases,
        reach = reach ?: base.reach,
        // Where the search is centred, and how far it reaches. A market that takes one is asked
        // with it; the rest are measured against what their listings say.
        location = queryParameters["near"]?.takeIf { it.isNotBlank() } ?: base.location,
        radiusKm = queryParameters["radiusKm"]?.toIntOrNull() ?: base.radiusKm,
    )
}

/** A price in EUR cents, converting from the listing's own currency so cross-border results
 *  (PLN/SEK/DKK/…) rank by real value instead of raw amount. */
private fun priceEurCents(money: Money): Long =
    if (money.currency == Currency.EUR) money.amount
    else ExchangeRates.convert(money.amount, money.currency.name, "EUR")

/** How long one market may take before the stream gives up on it. Every crawler runs inside this,
 *  so a hung browser costs one market's results rather than the server's ability to search at all. */
private const val PLATFORM_BUDGET_MS = 180_000L

/**
 * How many markets are actually crawled at once.
 *
 * Launching all eleven at once does not make them finish sooner: the browser-driven ones queue on
 * the shared browser anyway, and each one's clock was already running while it waited. Kleinanzeigen
 * answers in six seconds and mobile.de in fourteen when they are asked alone; both were being given
 * up on as too slow. Four at a time keeps every running crawl actually running, and results still
 * stream in as each finishes.
 */
private val crawlSlots = kotlinx.coroutines.sync.Semaphore(4)

/** How long a whole search may hold its scrape permit, whatever the crawlers are doing. */
private const val SEARCH_BUDGET_MS = 420_000L

/** The car post-filter pipeline, run AFTER the crawl cache so a filter tweak re-filters cached
 *  listings instead of re-crawling: card-level filter → detail-verify the survivors → final
 *  filter. Specs come from structured sources only (card + detail table), never free-text
 *  guessing. Non-car queries pass through unchanged. */
private suspend fun carPostFilter(
    listings: List<Listing>,
    searchQuery: SearchQuery,
    isCarQuery: Boolean,
    crawler: Crawler,
): Pair<List<Listing>, List<DroppedListing>> {
    if (!isCarQuery) return listings to emptyList()
    val filters = searchQuery.toCarFilters() ?: CarFilters()
    // Parse specs from each card's own title/description first (mileage, year, power, …), so a
    // platform that ships no structured data — eBay, Kleinanzeigen — is still filterable: a stated
    // "345.000 km" becomes a value the mileage filter can exclude on.
    // A query that is itself after a part ("Crafter Drehkonsole") must not have the non-vehicle
    // guard strip those parts out; then it returns the parts the user asked for.
    val partsIntent = CarFilterEngine.isPartQuery(searchQuery.positiveText)
    val enriched = listings.map { VehicleTextParser.enrich(it) }
    val onTheCard = CarFilterEngine.partition(enriched, filters, keepNonVehicles = partsIntent)
    val detailed = DetailEnricher.enrich(onTheCard.kept, filters, crawler)
    val afterDetail = CarFilterEngine.partition(detailed, filters, keepNonVehicles = partsIntent)
    // What the criteria removed, named by the criterion that removed it — both passes, since a
    // listing can fail on its card and another only once its own page has been read.
    val dropped = (onTheCard.dropped + afterDetail.dropped).map { (listing, criterion) ->
        DroppedListing(listing, DropReason.VEHICLE_CRITERIA, criterion)
    }
    // A free-form ideal-car description ranks (not filters) the survivors by local semantic
    // similarity, so the best matches surface first. Skipped when none was given.
    val filtered = filters.idealDescription?.takeIf { it.isNotBlank() }
        ?.let { CarCriteriaScorer.rank(afterDetail.kept, it) } ?: afterDetail.kept
    return filtered to dropped
}

/** Resolve every listing's location (zip/city + country) to coordinates, and fill in distanceKm
 *  when the search carries the searcher's position. Geocoding runs unconditionally so the client
 *  can measure distances itself later — picking "nearest first" then costs no crawl. A listing
 *  with no resolvable location is left as it is. */
/**
 * What a search reaches, for the markets that cannot be told.
 *
 * AutoScout24 takes a postcode and a radius, mobile.de a point and a radius, and they narrow at the
 * source. Everywhere else answers the whole country, so the listings that fall outside the search's
 * reach are removed here, measured from where the search is centred rather than from the device. A
 * listing whose market never says where it is stays: not knowing is not the same as being far.
 */
private fun outsideTheArea(listings: List<Listing>, query: SearchQuery): Pair<List<Listing>, List<Listing>> {
    val area = query.area(io.github.tieo.arbay.repo.ImportSettingsStore.current.homeCountry) ?: return listings to emptyList()
    val outside = mutableListOf<Listing>()
    val inside = listings.filter { listing ->
        val loc = listing.location ?: return@filter true
        val coords = if (loc.latitude != null && loc.longitude != null) loc.latitude!! to loc.longitude!!
        else Geocoder.resolve(loc.country, loc.zip, loc.city) ?: return@filter true
        val km = Geocoder.haversine(area.latitude, area.longitude, coords.first, coords.second)
        if (km > area.radiusKm) { outside += listing; false } else true
    }
    return inside to outside
}

/**
 * Say how a listing is sold, for the markets where there is only one answer.
 *
 * Two markets of twenty-eight hold auctions, and they say so on each listing. Everywhere else every
 * listing is sold at the price it states, which the crawlers left unsaid rather than untrue: the
 * results then read as 105 listings whose sale type was unknown against 26 that stated one, and a
 * reader taking auctions out of the screen had to take the unknown ones with them.
 */
private fun statedSale(listings: List<Listing>): List<Listing> = listings.map { l ->
    if (l.saleType != null) l
    else if (CrawlerRegistry.crawlerFor(l.platformId) is SellsByAuction) l
    else l.copy(saleType = SaleType.FIXED_PRICE)
}

/** Everything a listing gains on its way out of the server: where it is, how far that is from the
 *  reader, and how it is sold. */
private fun asDelivered(listings: List<Listing>, query: SearchQuery): List<Listing> {
    val lat = query.userLat
    val lon = query.userLon
    return statedSale(listings).map { l ->
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
 *  its country filter, the national sites reach markets it covers thinly. Shared with the
 *  client for the same reason as [GENERAL_PLATFORMS]. */
private val CAR_PLATFORMS = MarketSets.vehicles

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

        // A suggested term per language, for the searcher to look at, edit and accept. Suggesting
        // is all this does: what a market is asked is what the search carries, so a translation
        // reaches a market only after someone has seen it.
        get("/term-suggestions") {
            val text = call.request.queryParameters["q"].orEmpty()
            if (text.isBlank()) throw BadRequestException("Missing query parameter 'q'")
            val languages = call.request.queryParameters["languages"]
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: CrawlerRegistry.supportedPlatforms().map { it.searchLanguage }.distinct()
            val suggestions = languages.filter { it != "de" }.associateWith { language ->
                Translator.translate(text, "de", language)
            }
            // A language whose suggestion came back as the text itself is one the translator could
            // not answer for; saying so beats offering the same words as if they were a translation.
            call.respond(TermSuggestions(
                text = text,
                suggestions = suggestions.filterValues { it != text },
                unavailable = suggestions.filterValues { it == text }.keys.toList(),
            ))
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

        /** The page a market actually served, through the same fetch a crawl uses. A parser is
         *  fixed against the markup in front of it, and a market that answers a crawl while
         *  refusing everything else leaves no other way to see it. */
        get("/page") {
            val url = call.request.queryParameters["url"] ?: throw BadRequestException("Missing url")
            // The page a crawler would see, through the same tiers a crawl uses: eBay answers a
            // plain client with a 1.8 KB challenge, and answers TLS impersonation with a 403 on the
            // pages it defends hardest, which is no use to anyone fixing a parser against its
            // markup. `prime` is the page to arrive from, the way a real reader would.
            val prime = call.request.queryParameters["prime"]
            // mobile.de answers a plain client, TLS impersonation and the ordinary browser tier
            // with nothing at all — it is reachable only through the stealth sidecar its crawler
            // uses, which is also the only way to check one of its filter parameters by hand.
            val html = if (call.request.queryParameters["stealth"]?.toBooleanStrictOrNull() == true) {
                val marker = call.request.queryParameters["wait"] ?: "result-listing"
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.github.tieo.arbay.crawler.StealthBrowserClient.fetchRendered(url, marker, waitSeconds = 40)
                }
            } else if (call.request.queryParameters["browser"]?.toBooleanStrictOrNull() == true) {
                io.github.tieo.arbay.crawler.fetchWithFallback(
                    io.github.tieo.arbay.crawler.CrawlerRegistry.httpClient, url, "debug",
                    primeUrl = prime, browserOnly = true,
                )
            } else {
                runCatching { io.github.tieo.arbay.crawler.CurlCffiClient.fetch(url, primeUrl = prime) }
                    .getOrElse {
                        io.github.tieo.arbay.crawler.fetchHttp(
                            io.github.tieo.arbay.crawler.CrawlerRegistry.httpClient, url, "debug",
                        )
                    }
            }
            call.respondText(html, ContentType.Text.Plain)
        }

        // Everything one listing's own page says, for the sheet that shows one listing: the
        // seller's whole text, the specs the card omits, and where the thing is. Asked when
        // someone opens it, since an item page costs a browser load on the markets that defend
        // them — a price worth paying for the one listing being read and not for fifty being
        // scrolled past.
        get("/listing-detail") {
            val url = call.queryParameters["url"] ?: throw BadRequestException("Missing url")
            val platform = call.queryParameters["platform"]?.let { runCatching { PlatformId.valueOf(it) }.getOrNull() }
                ?: throw BadRequestException("Missing or unknown platform")
            val id = call.queryParameters["id"] ?: url
            io.github.tieo.arbay.crawler.DetailCache.get(id)?.let { return@get call.respond(it) }
            val crawler = CrawlerRegistry.crawlerFor(platform)
                ?: throw BadRequestException("No crawler for $platform")
            val stub = Listing(
                id = id, platformId = platform, externalId = id, url = url, title = "",
                price = Money(0, Currency.EUR), scrapedAt = kotlinx.datetime.Clock.System.now(),
            )
            val detail = crawler.fetchDetail(stub)
            if (detail == null) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                io.github.tieo.arbay.crawler.DetailCache.put(id, detail)
                call.respond(detail)
            }
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

            val isCar = isCarQuery(query)
            val platforms = if (platformName != null) {
                val platform = runCatching { PlatformId.valueOf(platformName) }.getOrNull()
                    ?: throw BadRequestException("Unknown platform: $platformName")
                listOf(platform)
            } else {
                defaultPlatforms(isCar)
            }

            val soldOnly = call.queryParameters["sold"]?.toBooleanStrictOrNull() ?: false
            val category = if (isCar) MarketGroup.VEHICLES else MarketGroup.GENERAL
            val searchQuery = call.applySearchExtras(call.applyCarFilters(SearchQuery(text = query, soldOnly = soldOnly, category = category)))

            val permit = call.acquireScrapeSlot() ?: return@get
            val results = try {
                val isCarQuery = isCar
                val perPlatform = platforms.map { platformId ->
                    val crawler = CrawlerRegistry.crawlerFor(platformId) ?: return@map emptyList()
                    // Cross-border markets are searched in their own language.
                    val pq = localizedQuery(searchQuery, platformId)
                    // The cache holds the market's whole answer; the search judges it on the way
                    // out, here and on every later hit alike, so tweaking a filter re-filters
                    // cached listings instead of re-crawling. Skip the crawl (serve cache only)
                    // while the platform is cooling down from a recent block — hitting it again
                    // would deepen the block.
                    val answer = QueryResultCache.get(platformId, pq)
                        ?: if (BlockCooldown.isCoolingDown(platformId)) emptyList()
                        else crawler.trackedSearch(pq, corpusBackground(listingRepo))
                            .also { QueryResultCache.put(platformId, pq, it) }
                    val classified = RelevanceFilter.filter(answer, pq).map { SoldDetector.classify(it) }
                    val result = asDelivered(
                        carPostFilter(classified, pq, isCarQuery, crawler).first, pq,
                    )
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

            val isCarQuery = isCarQuery(query)
            val platforms = if (platformName != null) {
                val platform = runCatching { PlatformId.valueOf(platformName) }.getOrNull()
                    ?: throw BadRequestException("Unknown platform: $platformName")
                listOf(platform)
            } else if (platformNames != null) {
                platformNames.split(",").mapNotNull { runCatching { PlatformId.valueOf(it.trim()) }.getOrNull() }
            } else {
                defaultPlatforms(isCarQuery)
            }

            val category = if (isCarQuery) MarketGroup.VEHICLES else MarketGroup.GENERAL
            val searchQuery = call.applySearchExtras(call.applyCarFilters(SearchQuery(text = query, category = category)))

            val permit = call.acquireScrapeSlot() ?: return@get
            try {
            withTimeoutOrNull(SEARCH_BUDGET_MS) {
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
                          // A crawler that never returns must not outlive the request. Without this
                          // its coroutine holds the stream open, the stream holds the scrape permit,
                          // and the server refuses every later search as busy until it restarts.
                          // The budget starts when the crawl does, not when it joins the queue.
                          crawlSlots.withPermit {
                          withTimeoutOrNull(PLATFORM_BUDGET_MS) crawl@{
                            val crawler = CrawlerRegistry.crawlerFor(platformId)
                            if (crawler == null) {
                                resultChannel.send(CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_ERROR,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    error = "No crawler available",
                                ))
                                return@crawl
                            }

                            // A market is asked in its own language only when the search carries a
                            // term for that language; the term is reported so the app shows what
                            // each site was actually asked.
                            val pq = localizedQuery(searchQuery, platformId)
                            // Set when the market says it answered from one of its own categories.
                            val answeredFromCategory = java.util.concurrent.atomic.AtomicBoolean(false)
                            val categoryEmitter = io.github.tieo.arbay.crawler.CategoryAnswerEmitter {
                                answeredFromCategory.set(true)
                            }
                            val termsUsed = java.util.Collections.synchronizedList(mutableListOf<String>())
                            val termsEmitter = TermsUsedEmitter { t ->
                                if (t.isNotBlank() && t !in termsUsed) termsUsed += t
                            }
                            // Keyed by the word, so the entry a follow-up search fills in with what
                            // it added replaces the one written before it ran.
                            val verdicts = java.util.Collections.synchronizedMap(LinkedHashMap<String, SuggestedTerm>())
                            val verdictEmitter = TermVerdictEmitter { v -> verdicts[v.term.lowercase()] = v }

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
                                // The same judging the fresh crawl gets, over the same answer: the
                                // reasons are worked out here rather than stored, so re-opening a
                                // search shows what it removed and why, and a blocked word dropped
                                // in the meantime brings its listings back at once.
                                val partitioned = RelevanceFilter.partition(cached, pq)
                                val classified = partitioned.kept.map { SoldDetector.classify(it) }
                                val done = finishedResults(classified, pq, isCarQuery, crawler, listingRepo)
                                resultChannel.send(CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_DONE,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    resultCount = done.listings.size,
                                    rawCount = cached.size,
                                    listings = done.listings,
                                    dropped = (
                                        partitioned.dropped + done.criteriaDropped +
                                            done.tooFar.map { DroppedListing(it, DropReason.TOO_FAR) }
                                        ).take(80),
                                    fromCache = true,
                                    facets = done.facets,
                                ))
                                return@crawl
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
                                return@crawl
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
                                val fresh = asDelivered(card.filter { emittedIds.add(it.id) }, pq)
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
                                    kotlinx.coroutines.withContext(progressEmitter + partialEmitter + captchaEmitter + termsEmitter + verdictEmitter + categoryEmitter) {
                                        crawler.searchAllSpellings(pq, corpusBackground(listingRepo))
                                    }
                                }
                                // A crawler that does not stream per page (single-fetch, or one not
                                // yet wired) still surfaces its whole parsed set here, before the
                                // detail-enrichment step below adds latency. Already-streamed pages
                                // are deduped inside the emitter, so a per-page crawler emits nothing
                                // extra.
                                partialEmitter.emit(rawResults)
                                // A market that sent back a page without one word of the search on
                                // it answered a different question, and its answer holds nothing to
                                // filter. Anything else is judged listing by listing, so a market
                                // that ran the search keeps whatever of it matched.
                                // A market answering with one title over and over is a parser
                                // reading the wrong node, which otherwise shows up only as results
                                // quietly going missing.
                                io.github.tieo.arbay.crawler.repeatedTitleReport(rawResults)?.let { report ->
                                    CrawlerStatusTracker.recordError(platformId, report, ErrorType.PARSE_ERROR)
                                    ErrorSnapshotStore.capture(
                                        platform = platformId.name, query = pq.text,
                                        error = CrawlerBlockedException(report, ErrorType.PARSE_ERROR),
                                        errorType = ErrorType.PARSE_ERROR,
                                        url = rawResults.firstOrNull()?.url,
                                        html = rawResults.take(25).joinToString("\n") { "${it.title}\t${it.url}" },
                                    )
                                }
                                val ignoredSearch = RelevanceFilter.answeredSomethingElse(
                                    rawResults, pq,
                                    askedInItsOwnLanguage(pq, platformId) && !answeredFromCategory.get(),
                                )
                                if (ignoredSearch != null) {
                                    CrawlerStatusTracker.recordError(platformId, ignoredSearch, ErrorType.IRRELEVANT_RESULTS)
                                } else {
                                    CrawlerStatusTracker.recordSuccess(platformId, rawResults.size)
                                }
                                val partitioned = RelevanceFilter.partition(
                                    if (ignoredSearch != null) emptyList() else rawResults, pq,
                                )
                                val classified = partitioned.kept.map { SoldDetector.classify(it) }
                                // Cache what the market actually sent, judged nowhere yet: the
                                // relevance rules, the car post-filter and the search's area all
                                // run over it on the way out, here and on a later cache hit alike,
                                // so a filter tweak re-filters without re-crawling and nothing the
                                // search removed is lost between the two.
                                QueryResultCache.put(platformId, pq, rawResults)
                                val done = finishedResults(classified, pq, isCarQuery, crawler, listingRepo)
                                val results = done.listings
                                val facets = done.facets
                                val dropped = if (ignoredSearch != null) {
                                    rawResults.map { DroppedListing(it, DropReason.MARKET_IGNORED_SEARCH) }
                                } else {
                                    partitioned.dropped + done.criteriaDropped +
                                        done.tooFar.map { DroppedListing(it, DropReason.TOO_FAR) }
                                }

                                CrawlerSearchEvent(
                                    type = CrawlerEventType.PLATFORM_DONE,
                                    platform = platformId.name,
                                    platformName = platformId.displayName,
                                    resultCount = results.size,
                                    rawCount = rawResults.size,
                                    listings = results,
                                    // Capped: a market can return hundreds, and this rides the same
                                    // stream as the results themselves. Kept in the order the
                                    // market ranked them, so the ones it thought most relevant —
                                    // the ones a wrong filter would be hiding — are the ones sent.
                                    dropped = dropped.take(80),
                                    termsUsed = termsUsed.toList(),
                                    suggestedTerms = verdicts.values.toList(),
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
                                    return@crawl
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
                            // A market that says where a thing is only on the thing's own page —
                            // eBay — is asked afterwards, not before: the cards are on screen the
                            // moment the crawl is done, and the places fill in behind them. The
                            // app replaces a market's listings when that market reports again, so
                            // the second report is the same listings with an address on them.
                            if (event.type == CrawlerEventType.PLATFORM_DONE &&
                                event.listings.any { it.location == null }
                            ) {
                                val placed = io.github.tieo.arbay.crawler.LocationEnricher
                                    .enrich(event.listings, crawler)
                                if (placed.zip(event.listings).any { (a, b) -> a.location != b.location }) {
                                    resultChannel.send(
                                        event.copy(listings = asDelivered(placed, pq)),
                                    )
                                }
                            }
                          } ?: resultChannel.send(CrawlerSearchEvent(
                              type = CrawlerEventType.PLATFORM_ERROR,
                              platform = platformId.name,
                              platformName = platformId.displayName,
                              error = "gave nothing within ${PLATFORM_BUDGET_MS / 1000}s and was given up on",
                              errorType = "TIMEOUT",
                          ))
                          }
                        }
                    }
                    // Close channel once all platform coroutines finish
                    launch { jobs.joinAll(); resultChannel.close() }

                    var completed = 0
                    // A market reports once when it is finished. It may report again with the same
                    // listings placed on the map, and that later report must not count as another
                    // market finishing, or the progress line reads "12 of 11 markets".
                    val alreadyCounted = mutableSetOf<String>()
                    for (event in resultChannel) {
                        val isProgress = event.type == CrawlerEventType.PLATFORM_PROGRESS
                        val finishes = event.type == CrawlerEventType.PLATFORM_DONE ||
                            event.type == CrawlerEventType.PLATFORM_ERROR
                        if (finishes && alreadyCounted.add(event.platform)) completed++
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

            val category = if (isCarQuery(query)) MarketGroup.VEHICLES else MarketGroup.GENERAL
            val searchQuery = call.applyCarFilters(SearchQuery(text = query, category = category))
            val permit = call.acquireScrapeSlot() ?: return@get
            val results = try { crawler.trackedSearch(searchQuery, corpusBackground(listingRepo)) } finally { permit.release() }

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
