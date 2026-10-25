package io.github.tieo.arbay.crawler

import kotlin.math.roundToLong
import kotlin.math.roundToInt
import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.biggestLosses
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.ImportSettings
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.NotificationSubfilter
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.learning
import io.github.tieo.arbay.model.SavedSearchStatus
import io.github.tieo.arbay.model.SubfilterMatch
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.model.displayName
import io.github.tieo.arbay.model.landedPrice
import io.github.tieo.arbay.repo.ImportSettingsStore
import io.github.tieo.arbay.repo.ListingArchive
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.ProductRepo
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.File
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.tieo.arbay.model.CrawlerEventType
import io.github.tieo.arbay.model.CrawlerSearchEvent
import io.github.tieo.arbay.model.toCarFilters
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import org.slf4j.LoggerFactory

/**
 * Re-runs a saved search (TrackedProduct) on the schedule set on that search, so a bookmark keeps
 * finding new stock without the user re-searching. What it finds shows on the saved search itself,
 * silently; the only thing held for the phone to notify about is a listing matching one of that
 * search's own notification subfilters. A search with no subfilter therefore never interrupts —
 * saying "something turned up" without being asked to is what the bookmark's own count is for.
 *
 * Every search is off by default: [TrackedProduct.autoFetch] is opted into per search, not turned
 * on for the whole account by one flag, so a search never crawls in the background unless someone
 * specifically asked it to. A tick every [TICK_MS] checks every saved search's own interval rather
 * than running them all in lockstep; a run reuses the same throttle, per-platform pacing,
 * block-cooldown and request budget as an interactive search (via trackedSearch).
 */
class SavedSearchMonitor(
    private val productRepo: ProductRepo,
    private val listingRepo: ListingRepo,
    /** This server's own address, which a fit alert searches through like the app does. */
    private val selfUrl: String? = null,
    /** The seller's own text of an offer, read off its page; null when it cannot be read. */
    private val adText: suspend (Listing) -> String? = ::readAdText,
) {

    private val log = LoggerFactory.getLogger(SavedSearchMonitor::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    // How often a search's own interval is checked, not how often a search itself is re-crawled.
    private val TICK_MS = 60_000L

    // A floor under whatever interval a search is given, so a mistyped "5" does not turn into a
    // five-minute crawl loop — the flag risk the anti-block work manages is per-crawl, not per-search.
    private val MIN_INTERVAL_MIN = 30

    /** How many ad pages a learning rule reads, cheapest first, looking for one not sold broken:
     *  each is a request to the market inside its per-minute window. */
    private val MAX_AD_READS = 5

    /** Prefix of the [seen] entry that marks a fit alert's first run as done. */
    private val FIT_KEY = "fit:"

    // Subfilter matches found since the phone last polled. Held rather than sent: the notification
    // is raised on the device, so this waits for the device to ask.
    private val pendingSubfilterMatches = mutableListOf<SubfilterMatch>()

    /** Subfilter matches found since the last call, handed to the phone that will raise the
     *  notifications. */
    fun drainSubfilterMatches(): List<SubfilterMatch> = synchronized(pendingSubfilterMatches) {
        pendingSubfilterMatches.toList().also { pendingSubfilterMatches.clear() }
    }

    private val json = Json { ignoreUnknownKeys = true }

    // The watch loop writes [seen], [unopened], [lastNew] and [lastRun] while the app's requests
    // read and clear them, so every touch of them holds this lock. Crawling happens outside it.
    private val stateLock = Any()

    private val seenFile = DataDir.file("saved_search_seen.json")
    // Listing ids already reported, keyed by saved-search id, so only genuinely new stock alerts.
    private val seen: MutableMap<String, MutableSet<String>> = loadSeen()

    /**
     * What each saved search has found and when, in one file.
     *
     * [unopened] is what it has turned up since someone last opened it, which is the count on the
     * bookmark. [lastNew] is the most recent batch it found, kept past the point where opening
     * clears [unopened], so "show me what you found" still has something to show the second time.
     * [lastRunAtMillis] is persisted because it is what decides when a search is next due: held
     * only in memory, every watched search came up due at once on the first tick after a restart
     * and they all crawled together, which is exactly the traffic shape that gets an address
     * blocked. [foundAtMillis] is when the watch found each id still held in either set.
     * [unansweredAtLastRun] is each market, by [PlatformId] name, that gave a search's last run no
     * answer, with why: a run every market failed finds nothing and otherwise reads as a quiet one.
     */
    @Serializable
    private data class SearchState(
        val unopened: Map<String, Set<String>> = emptyMap(),
        val lastNew: Map<String, Set<String>> = emptyMap(),
        val lastRunAtMillis: Map<String, Long> = emptyMap(),
        val foundAtMillis: Map<String, Map<String, Long>> = emptyMap(),
        val unansweredAtLastRun: Map<String, Map<String, String>> = emptyMap(),
    )

    private val stateFile = DataDir.file("saved_search_state.json")
    private val legacyStatusFile = DataDir.file("saved_search_status.json")
    private val legacyLastNewFile = DataDir.file("saved_search_last_new.json")

    private val unopened: MutableMap<String, MutableSet<String>>
    private val lastNew: MutableMap<String, MutableSet<String>>
    private val lastRun: MutableMap<String, Long>
    private val foundAt: MutableMap<String, MutableMap<String, Long>>
    private val unanswered: MutableMap<String, Map<String, String>>

    init {
        val state = loadState()
        unopened = state.unopened.mapValuesTo(HashMap()) { it.value.toMutableSet() }
        lastNew = state.lastNew.mapValuesTo(HashMap()) { it.value.toMutableSet() }
        lastRun = HashMap(state.lastRunAtMillis)
        foundAt = state.foundAtMillis.mapValuesTo(HashMap()) { HashMap(it.value) }
        unanswered = HashMap(state.unansweredAtLastRun)
    }

    /** What every saved search has been doing, for the app's list of them. */
    fun statuses(): List<SavedSearchStatus> {
        // A drop found before its offer was put away waits until the search is opened, and a put
        // away offer is news to nobody however far it comes down.
        val hidden = io.github.tieo.arbay.repo.UserStateStore.hiddenOffers()
        return productRepo.getAll().map { product -> synchronized(stateLock) {
            SavedSearchStatus(
                productId = product.id,
                watched = product.autoFetch.enabled,
                lastRunAtMillis = lastRun[product.id],
                newSinceOpened = unopened[product.id]?.size ?: 0,
                newListingIds = unopened[product.id]?.toList().orEmpty(),
                foundAtMillis = foundAt[product.id]?.toMap().orEmpty(),
                missingTopOffers = TopOffers.missing(product.id),
                priceDrops = PriceDrops.of(product.id).filterNot { it.listingId in hidden },
                unansweredMarkets = unanswered[product.id].orEmpty(),
            )
        } }
    }

    /** Called when a saved search is opened: what was waiting has now been seen. */
    fun markOpened(productId: String) {
        synchronized(stateLock) { unopened.remove(productId) }
        PriceDrops.opened(productId)
        saveStatus()
    }

    /**
     * The listings this saved search turned up, as they were when it found them — what is still
     * unseen, or the last batch it found once that has been opened.
     *
     * Served from what was stored at crawl time rather than looked up again: a listing found
     * overnight can be sold or deleted by morning, and asking the platform for it then answers
     * "gone" rather than showing what the watch actually saw. Falls back to the archive, which
     * outlives a restart and mirrors the images too.
     */
    fun newListings(productId: String): List<Listing> {
        val ids = synchronized(stateLock) {
            unopened[productId]?.takeIf { it.isNotEmpty() }?.toList() ?: lastNew[productId].orEmpty().toList()
        }
        // The archived copy first: it is the one whose images are mirrored here, so it still shows
        // a photo after the platform drops the listing. The repo copy stands in while archiving is
        // still in flight, since that runs in the background after a crawl.
        return ids.mapNotNull { ListingArchive.get(it) ?: listingRepo.getById(it) }
    }

    fun start() {
        if (job?.isActive == true) return
        log.info("Saved-search updater on, checking every {} min which searches are due", TICK_MS / 60_000)
        job = scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Saved-search tick failed: {}", e.message)
                }
                delay(TICK_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One pass over every saved search: run whichever ones are both watched and due. */
    private suspend fun tick() {
        val now = System.currentTimeMillis()
        for (product in productRepo.getAll()) {
            if (!product.autoFetch.enabled) continue
            val intervalMs = product.autoFetch.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MIN) * 60_000L
            val last = synchronized(stateLock) { lastRun[product.id] } ?: 0L
            if (now - last < intervalMs) continue
            try {
                if (product.fitAlert.enabled && selfUrl != null) runFit(product, selfUrl) else runProduct(product)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Saved-search run failed for {}: {}", product.name, e.message)
            }
        }
    }

    /** One saved search's own run. Seeds itself silently the first time it is ever run — the same
     *  search re-enabled later does not re-alert on a backlog it already knows about, since [seen]
     *  persists across restarts. */
    private suspend fun runProduct(product: TrackedProduct) {
        val platforms = product.searchQuery.platforms
            .filter { CrawlerRegistry.crawlerFor(it) != null }
            .ifEmpty { return }

        val found = LinkedHashMap<String, Listing>() // listing id -> listing
        val failed = HashMap<String, String>()
        val notTheThing = HashSet<String>() // offer keys some market's copy showed to be something else
        for (platformId in platforms) {
            if (BlockCooldown.isCoolingDown(platformId)) { failed[platformId.name] = "cooling down after a block"; continue }
            val crawler = CrawlerRegistry.crawlerFor(platformId) ?: continue
            // Asked exactly as the search asks when it is opened by hand, including a term the
            // searcher accepted for this market's language. A watch that asked something else
            // would notify about a different set of listings than the screen shows.
            val query = localizedQuery(product.searchQuery.copy(platforms = listOf(platformId)), platformId)
            // A crawler can hang for good (a stuck browser once held every search permit for
            // hours), and nobody hangs up on a background run, so without a limit one hang ends
            // every watch. A time limit that runs out is this market failing, not the watch being
            // stopped, so it is an answer of null here rather than a cancellation that would end the loop.
            val results = try {
                withTimeoutOrNull(120_000L) { crawler.trackedSearch(query) { term -> listingRepo.titleShareOfCorpus(term) } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.debug("saved-search {} on {} failed: {}", product.id, platformId, e.message?.take(60))
                failed[platformId.name] = e.message?.take(120) ?: e.javaClass.simpleName
                continue
            } ?: run {
                log.debug("saved-search {} on {} gave nothing within 120s", product.id, platformId)
                failed[platformId.name] = "no answer within 120 s"
                continue
            }
            val judged = RelevanceFilter.partition(results, query)
            judged.kept.forEach { found[it.id] = it }
            judged.dropped.filter { it.reason in WHAT_THE_ITEM_IS }.forEach { notTheThing += offerKey(it.listing.id) }
        }
        // One eBay item is listed on every eBay site under its number, titled in each site's
        // language: the fan insert Lägler's US dealer sells read as a part in English and French,
        // and as a Hummel in Spanish, which went out under a 700 € alert. What one copy shows the
        // item to be, every copy is.
        found.keys.removeAll { offerKey(it) in notTheThing }

        // An offer the user put away is a judgement on it: a fake or a scam priced below
        // everything real would otherwise set the price every later find has to beat.
        val hidden = io.github.tieo.arbay.repo.UserStateStore.hiddenOffers()
        found.keys.removeAll(hidden)
        val learned = learn(product, found.values, hidden)
        noteUnanswered(product.id, failed)
        val freshListings = record(learned, found) ?: return

        // Named notification subfilters someone set on this search specifically — a listing can
        // match more than one, and each match is worth its own notification since each names a
        // different reason the person cared enough to ask for it.
        val now = Clock.System.now()
        val subfilterMatches = learned.notificationSubfilters
            .filter { it.enabled && !it.learning(now) }
            .flatMap { sf ->
                freshListings
                    .filter {
                        // A notification is an interruption, so it has to be about the thing that
                        // was searched for, not merely about something the market chose to answer
                        // with. Where the search's own words are written by nobody, everything a
                        // market sends is kept for the screen — and none of it is worth waking
                        // someone for.
                        RelevanceFilter.carriesAWordOfTheSearch(it, product.searchQuery) && isTheThing(it) &&
                            matchesSubfilter(it, sf) && beatsTheBestSeen(it, sf) && worthInterrupting(
                            it,
                            product.autoFetch.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MIN),
                        )
                    }
                    .map { sf to it }
            }
            // The card rarely says a thing is broken and the ad's text often does; the few
            // offers worth a push are read before one goes out.
            .filterNot { (_, l) -> soldBroken(l) }
        if (subfilterMatches.isNotEmpty()) {
            synchronized(pendingSubfilterMatches) {
                subfilterMatches.take(10).forEach { (sf, l) ->
                    pendingSubfilterMatches += SubfilterMatch(
                        listingId = l.id,
                        searchName = product.name,
                        subfilterName = sf.displayName,
                        title = l.title,
                        url = l.url,
                        // The number the subfilter decided on, so the notification cannot announce
                        // €145 for a listing it let through at €149 landed.
                        priceText = eurCents(l.landedPrice(ImportSettingsStore.current))
                            ?.let { "€${it / 100}" },
                        locationText = l.location?.let { it.city ?: it.country },
                        saleType = l.saleType,
                        auctionEndsAt = l.auctionEndsAt,
                    )
                }
            }
        }

        if (subfilterMatches.isNotEmpty()) pushSubfilterMatches(product, subfilterMatches)
        AlertFeed.record(subfilterMatches.map { (sf, l) ->
            FoundAlert(
                seq = 0, savedSearchId = product.id, searchName = product.name, rule = sf.displayName,
                listingId = l.id, title = l.title, url = l.url,
                priceEur = eurCents(l.landedPrice(ImportSettingsStore.current))?.let { it / 100 }, at = Clock.System.now(),
            )
        })

        log.info(
            "saved-search {}: {} new, {} subfilter matches",
            product.name, freshListings.size, subfilterMatches.size,
        )
        // Which listing, not just how many. A count answers "did it fire"; deciding whether a
        // notification was worth having needs the thing it fired about, and asking afterwards what
        // an alert had been for could only be guessed at from what happened to still be stored.
        subfilterMatches.forEach { (sf, l) ->
            log.info(
                "  alert: {} · {} — {} · {} · {}",
                sf.displayName,
                product.name,
                eurCents(l.landedPrice(ImportSettingsStore.current))?.let { "€${it / 100}" } ?: "no price",
                l.title.take(70),
                l.url,
            )
        }
        saveSeen()
    }


    /** Which markets gave [productId]'s run that just ended no answer, kept until its next run. */
    private fun noteUnanswered(productId: String, failed: Map<String, String>) {
        synchronized(stateLock) { if (failed.isEmpty()) unanswered.remove(productId) else unanswered[productId] = failed.toMap() }
    }

    /**
     * Notes what one run of [product] found and returns what is new since earlier runs, or null
     * when there is nothing to tell: no market answered, nothing is new, or this is the search's
     * first run, which seeds itself silently.
     */
    internal fun record(product: TrackedProduct, found: Map<String, Listing>): List<Listing>? {
        synchronized(stateLock) { lastRun[product.id] = System.currentTimeMillis() }

        // Every market refused or failed. Recording that as this search's first, silent run would
        // spend the one chance to seed quietly on nothing, and the next run that does reach a
        // market would then announce its entire result set as new stock. Blocks are routine, so
        // this is the common case, not a corner: leave [seen] untouched and try again next tick.
        if (found.isEmpty()) {
            saveStatus()
            return null
        }
        // Every offer found, not only the new ones: a cut is news about an offer already known.
        PriceDrops.update(product.id, found.values)

        val silent: Boolean
        val fresh: List<String>
        synchronized(stateLock) {
            silent = product.id !in seen
            val known = seen.getOrPut(product.id) { mutableSetOf() }
            // One eBay item is listed on every eBay site under the same number: a Künzle edge
            // sander alerted from ebay.fr came again the next morning from ebay.de.
            val knownOffers = known.mapTo(HashSet(), ::offerKey)
            fresh = found.keys.filter { offerKey(it) !in knownOffers }.distinctBy(::offerKey)
            known.addAll(found.keys)
            if (fresh.isNotEmpty() && !silent) {
                unopened.getOrPut(product.id) { mutableSetOf() }.addAll(fresh)
                lastNew[product.id] = fresh.toMutableSet()
                val now = System.currentTimeMillis()
                foundAt.getOrPut(product.id) { HashMap() }.apply { fresh.forEach { put(it, now) } }
            }
        }
        if (fresh.isNotEmpty() && !silent) {
            saveStatus()
            // Stored as found, so the app can show this batch later without asking the platform
            // again — which by then may no longer have it. Archiving mirrors the images too.
            listingRepo.upsertBatch(fresh.mapNotNull { found[it] })
            fresh.mapNotNull { found[it] }.forEach(io.github.tieo.arbay.repo.ListingArchive::refresh)
        }
        saveSeen()
        if (fresh.isEmpty() || silent) return null
        return fresh.mapNotNull { found[it] }
    }

    private val loopback by lazy { io.github.tieo.arbay.routes.loopbackClient() }

    /** Why a market gave a run no full answer; [refusal] when the market turned the run away. */
    private class MarketFailure(val message: String, val refusal: Boolean)

    /** What one search through the app's stream brought back. */
    private class FitCrawl(
        val found: LinkedHashMap<String, Listing> = LinkedHashMap(),
        /** The rule that removed each offer the markets sent, so a best offer that stops coming
         *  back is told apart: removed by the search, or not sent at all. */
        val ruledOut: HashMap<String, String> = HashMap(),
        /** Each market, by [PlatformId] name, that ended in an error rather than an answer. */
        val failed: HashMap<String, MarketFailure> = HashMap(),
    )

    /** One search of [q] through this server's own stream, as the app runs it; [platforms] narrows it
     *  to those markets, and null searches every market the saved search names. */
    private suspend fun fitCrawl(
        selfUrl: String,
        q: io.github.tieo.arbay.model.SearchQuery,
        crawl: io.github.tieo.arbay.model.CarFilters,
        home: Pair<Double, Double>?,
        platforms: List<PlatformId>?,
    ): FitCrawl {
        val result = FitCrawl()
        // What each market streamed before it finished, kept where it is given up on instead: a
        // market that timed out after sending 275 vans otherwise counted as having sent none, and
        // its best ones as lost.
        val streamed = HashMap<String, MutableList<Listing>>()
        loopback.prepareGet("$selfUrl/api/crawler/search/stream") {
            parameter("q", q.text)
            (platforms ?: q.platforms).takeIf { it.isNotEmpty() }?.let { parameter("platforms", it.joinToString(",") { p -> p.name }) }
            q.excludeKeywords.takeIf { it.isNotEmpty() }?.let { parameter("excludeKeywords", it.joinToString(",")) }
            q.aliases.takeIf { it.isNotEmpty() }?.let { parameter("aliases", it.joinToString(",")) }
            parameter("carFilters", json.encodeToString(io.github.tieo.arbay.model.CarFilters.serializer(), crawl))
            q.productWishes?.takeUnless { it.isEmpty }?.let { parameter("wishes", json.encodeToString(io.github.tieo.arbay.model.ProductWishes.serializer(), it)) }
            home?.let { (lat, lon) -> parameter("lat", lat); parameter("lon", lon) }
            parameter("unattended", true)
        }.execute { response ->
            if (!response.status.isSuccess()) return@execute
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank()) continue
                val event = runCatching { json.decodeFromString<CrawlerSearchEvent>(line) }.getOrNull() ?: continue
                when (event.type) {
                    CrawlerEventType.PLATFORM_PROGRESS -> streamed.getOrPut(event.platform) { mutableListOf() } += event.listings
                    CrawlerEventType.PLATFORM_ERROR -> {
                        streamed.remove(event.platform)?.forEach { result.found.putIfAbsent(it.id, it) }
                        val type = event.errorType
                        result.failed[event.platform] = MarketFailure(
                            message = event.error ?: type ?: "error",
                            refusal = type == "COOLING_DOWN" || ErrorType.entries.any { it.name == type && BlockCooldown.isBlock(it) },
                        )
                    }
                    CrawlerEventType.PLATFORM_DONE -> {
                        streamed.remove(event.platform)
                        event.listings.forEach { result.found[it.id] = it }
                        event.dropped.forEach { d -> result.ruledOut[d.listing.id] = listOfNotNull(d.reason.name.lowercase().replace('_', ' '), d.detail).joinToString(": ") }
                    }
                    else -> {}
                }
                if (event.type == CrawlerEventType.SEARCH_COMPLETE) break
            }
        }
        return result
    }

    /**
     * A saved search with a fit alert: searched exactly as the app searches it, so every listing
     * has had its ad read (size, drive, air conditioning, the dealer's reviews) and carries its
     * distance from home; then each new one is scored as a whole ([FitScore]) and the ones that fit
     * well are pushed to the phone ([Ntfy]).
     *
     * The criteria the score weighs are left out of the search itself, since as a filter they
     * would drop a van before it could be weighed: engine power, drive, wheelbase and seller type.
     * What stays is what narrows the markets to candidates at all: price, year, mileage, gearbox,
     * fuel, body and the van's size, which is also what makes the server open each ad.
     *
     * A new offer that one still online beats on price, power, age and mileage at once is not
     * pushed, however well it fits: the user would take the other one.
     */
    private suspend fun runFit(product: TrackedProduct, selfUrl: String) {
        val settings = product.fitAlert
        val q = product.searchQuery
        val wishes = q.toCarFilters() ?: io.github.tieo.arbay.model.CarFilters()
        // A search for a thing is weighed by its own wishes instead, read from the same offers.
        val things = q.productWishes?.takeUnless { it.isEmpty }
        val crawl = wishes.copy(
            minPowerKw = null, maxPowerKw = null, drivetrain = null,
            minWheelbaseMm = null, maxWheelbaseMm = null, sellerType = null,
        )
        // The user's home as the free-item profile names it, where the alert names none: it is one
        // place for every search, and a search that measured from nowhere weighed every offer's
        // distance as unknown.
        val home = (settings.home ?: io.github.tieo.arbay.classifier.FreeItemProfileStore.get()?.location)?.let(::homePoint)
        val crawled = fitCrawl(selfUrl, q, crawl, home, platforms = null)
        // A market that refused the run is asked again once its block cooldown is over: mobile.de
        // answers Access denied for a quarter of an hour now and then and is fine before and after,
        // and a run that took that answer as final lost every van of the market until the next
        // day's run (live, 2026-10-21: nine of the Crafter search's best ten).
        val refused = crawled.failed.filterValues { it.refusal }.keys.mapNotNull { name -> PlatformId.entries.firstOrNull { it.name == name } }
        if (refused.isNotEmpty()) {
            val wait = refused.maxOf { BlockCooldown.remainingMs(it) }
            log.info("fit alert {}: {} refused the run, asking again in {} s", product.name, refused.joinToString { it.displayName }, wait / 1000)
            delay(wait + 1_000L)
            val again = fitCrawl(selfUrl, q, crawl, home, platforms = refused)
            crawled.found.putAll(again.found)
            crawled.ruledOut.putAll(again.ruledOut)
            refused.forEach { crawled.failed.remove(it.name) }
            crawled.failed.putAll(again.failed)
        }
        val found = crawled.found
        val ruledOut = crawled.ruledOut
        noteUnanswered(product.id, crawled.failed.mapValues { it.value.message })
        // The saved market and country picks narrow what was fetched, as they do on the screen.
        val markets = q.showOnlyMarkets
        val countries = q.showOnlyCountries
        val shown = found.filterValues {
            (markets.isEmpty() && countries.isEmpty()) || it.platformId in markets ||
                io.github.tieo.arbay.model.MarketSets.countryOf(it.platformId) in countries
        }
        // A fit alert's own first run only learns what is there. The search may have run before
        // without it, on a plain crawl that never opened the ads, and everything this richer run
        // finds would count as new: switching the alert on once pushed four offers at once.
        val fitKey = "$FIT_KEY${product.id}"
        val firstFitRun = synchronized(stateLock) {
            (fitKey !in seen).also { first -> if (first) seen[fitKey] = mutableSetOf() }
        }
        val fresh = record(product, shown)
        // What the top of the search scores today, every offer counted once.
        val scores = bestCopyEach(
            shown.values.mapNotNull { l ->
                val fit = if (things != null) FitScore.score(l, things, FitScore.priceEur(l), settings.softDriveHours)
                    else FitScore.score(l, wishes, FitScore.priceEur(l), settings.softDriveHours)
                fit?.let { l to it }
            },
        ) { it.score }
        // Every run, quiet ones included: the best offers it ever ranked are looked for again.
        if (shown.isNotEmpty()) TopOffers.update(
            product.id, scores.map { (l, fit) -> l to fit.score }, shown.keys, ruledOut - shown.keys, TopOffers::adState,
            unanswered = crawled.failed.mapValues { it.value.message },
        )
        if (firstFitRun) {
            log.info("fit alert {}: first run, {} offers learned silently", product.name, shown.size)
            return
        }
        if (fresh == null) return
        val bar = maxOf(settings.minScore, scores.getOrNull(settings.topN - 1)?.second?.score ?: settings.minScore)
        val freshIds = fresh.mapTo(HashSet()) { it.id }
        val online = scores.map { it.first }
        val worth = scores.filter { (l, fit) -> l.id in freshIds && fit.score >= bar }.filter { (l, _) ->
            val better = FitScore.beatenBy(l, online) ?: return@filter true
            log.info("fit alert {}: {} beaten by {}", product.name, l.url, better.url)
            false
        }
        log.info("fit alert {}: {} new, {} worth a message", product.name, fresh.size, worth.size)
        worth.forEach { (l, fit) -> log.info("  fit {}: {} · {}", "%.2f".format(fit.score), fitLine(l, fit), l.url) }
        if (worth.isEmpty()) return
        AlertFeed.record(worth.map { (l, fit) ->
            FoundAlert(
                seq = 0, savedSearchId = product.id, searchName = product.name, rule = "fit ${(fit.score * 100).roundToInt()} %",
                listingId = l.id, title = l.title, url = l.url, priceEur = FitScore.priceEur(l).roundToLong(), at = Clock.System.now(),
            )
        })
        // One message per run, however many made it: the best one in full, opened by a tap, and a
        // line each for the next, with buttons. Several pushes at once were both noise and more
        // than ntfy.sh takes in a burst (the fifth was refused with 429).
        val (best, bestFit) = worth.first()
        val title = if (worth.size == 1) "${product.name}: ${fitLine(best, bestFit)}"
            else "${product.name}: ${worth.size} new in your top ${settings.topN}"
        val body = buildString {
            append(fitDetails(best, bestFit))
            worth.drop(1).take(4).forEach { (l, fit) -> append("\n· ").append(fitLine(l, fit)) }
        }
        val buttons = worth.drop(1).take(3).map { (l, _) ->
            Ntfy.Action(label = listOfNotNull(euros(l), l.location?.city).joinToString(" "), url = l.url)
        }
        Ntfy.send(title, body, click = best.url, tags = listOf(if (things != null) "hammer_and_wrench" else "truck"), actions = buttons)
    }

    /**
     * The search with each learning rule's [NotificationSubfilter.bestSeenEur] lowered to the
     * cheapest of [found] the rule passes, kept when it changed. Everything the markets answer
     * counts, not only what is new: the market as it stands is what a later find has to beat. An
     * auction's price is a bid so far, and what [pricesTheThing] turns away is not the thing at
     * its price, so none of them teaches anything.
     */
    internal suspend fun learn(product: TrackedProduct, found: Collection<Listing>, hidden: Set<String>): TrackedProduct {
        val now = Clock.System.now()
        val rules = product.notificationSubfilters.map { rule ->
            if (!rule.enabled || !rule.learning(now)) return@map rule
            // A bar set by an offer since put away was never the market's price: it is learned
            // again from what the markets answer now.
            val sf = if (rule.bestSeenListingId in hidden) rule.copy(bestSeenEur = null, bestSeenListingId = null) else rule
            val candidates = found
                .filter { it.saleType != SaleType.AUCTION && pricesTheThing(it) && RelevanceFilter.carriesAWordOfTheSearch(it, product.searchQuery) && matchesSubfilter(it, sf) }
                .mapNotNull { l -> eurCents(l.landedPrice(ImportSettingsStore.current))?.let { l to it } }
                .sortedBy { it.second }
            // Below what the rule already holds, cheapest first, the first whose ad does not say
            // it is sold broken: a defective drive is listed as one only in its text, and set
            // the bar at 100 € for 2 TB.
            val cheapest = candidates
                .takeWhile { (sf.bestSeenEur ?: Int.MAX_VALUE) * 100L > it.second }
                .take(MAX_AD_READS)
                .firstOrNull { !soldBroken(it.first) }
                ?: return@map sf
            val eur = ((cheapest.second + 99) / 100).toInt()
            if ((sf.bestSeenEur ?: Int.MAX_VALUE) <= eur) return@map sf
            log.info("saved-search {}: rule {} learned €{} from {}", product.name, sf.displayName, eur, cheapest.first.url)
            sf.copy(bestSeenEur = eur, bestSeenListingId = cheapest.first.id)
        }
        if (rules == product.notificationSubfilters) return product
        // The user may have changed the search while it ran; only the learned prices are written.
        val current = productRepo.getById(product.id) ?: return product
        val byId = rules.associateBy { it.id }
        val next = current.copy(notificationSubfilters = current.notificationSubfilters.map { sf ->
            val ran = byId[sf.id] ?: return@map sf
            val learnedEur = ran.bestSeenEur
            val relearned = sf.bestSeenListingId != null && sf.bestSeenListingId in hidden
            when {
                sf.learnUntil == null || learnedEur == null -> if (relearned) sf.copy(bestSeenEur = null, bestSeenListingId = null) else sf
                relearned || (sf.bestSeenEur ?: Int.MAX_VALUE) > learnedEur -> sf.copy(bestSeenEur = learnedEur, bestSeenListingId = ran.bestSeenListingId)
                else -> sf
            }
        })
        return productRepo.update(next) ?: product
    }

    /** Whether the offer's title or the seller's text says it is sold broken. */
    private suspend fun soldBroken(listing: Listing): Boolean =
        Condition.soldAsDefective(listing.title, adText(listing))

    /**
     * Subfilter matches to the phone through ntfy as well as the app: one message per run, the
     * cheapest in full and a tap away, the next ones as lines and buttons. Nothing is sent while
     * no topic is set.
     */
    private suspend fun pushSubfilterMatches(product: TrackedProduct, matches: List<Pair<NotificationSubfilter, Listing>>) {
        if (!Ntfy.configured) return
        fun price(l: Listing) = eurCents(l.landedPrice(ImportSettingsStore.current))
        fun line(l: Listing) = listOfNotNull(
            price(l)?.let { "%,d €".format(java.util.Locale.GERMANY, it / 100) },
            l.title.take(60),
            l.location?.let { it.city ?: it.country },
        ).joinToString(" · ")
        val listings = matches.map { it.second }.distinctBy { it.id }.sortedBy { price(it) ?: Long.MAX_VALUE }
        val best = listings.first()
        val title = if (listings.size == 1) "${product.name}: ${line(best)}"
            else "${product.name}: ${listings.size} new under your alert"
        val body = listings.take(5).joinToString("\n") { "· " + line(it) }
        val buttons = listings.drop(1).take(3).map { l ->
            Ntfy.Action(label = listOfNotNull(price(l)?.let { "%,d €".format(java.util.Locale.GERMANY, it / 100) }, l.location?.city).joinToString(" "), url = l.url)
        }
        Ntfy.send(title, body, click = best.url, tags = listOf("mag"), actions = buttons)
    }

    /** Where home is, from "70173 Stuttgart", a postcode, or a place name. */
    private fun homePoint(home: String): Pair<Double, Double>? {
        val zip = Regex("""\b\d{4,5}\b""").find(home)?.value
        val city = home.replace(Regex("""\b\d{4,5}\b"""), "").trim().takeIf { it.isNotEmpty() }
        return Geocoder.resolve(io.github.tieo.arbay.repo.ImportSettingsStore.current.homeCountry, zip, city)
    }

    private fun euros(l: Listing) = "%,d €".format(java.util.Locale.GERMANY, FitScore.priceEur(l).toLong())

    /** Price, place and drive: "23.950 € · Oldenburg · ~7 h". */
    private fun fitLine(l: Listing, fit: FitScore.Fit): String =
        listOfNotNull(euros(l), l.location?.city ?: l.location?.country, fit.driveHours?.let(FitScore::hoursLabel)).joinToString(" · ")

    /** Year, mileage, power, dealer, how well it fits and the wishes that cost it most: "price −10 · power −4 (103 kW)". */
    private fun fitDetails(l: Listing, fit: FitScore.Fit): String {
        val v = l.vehicle
        val seller = l.seller
        val dealer = seller?.name?.let { name ->
            name + (seller.rating?.let { " ${"%.1f".format(it)}★" + (seller.reviewCount?.let { n -> " ($n)" } ?: "") } ?: "")
        }
        val facts = listOfNotNull(
            fitLine(l, fit),
            v?.firstRegYear?.toString(),
            v?.mileageKm?.let { "%,d km".format(java.util.Locale.GERMANY, it) },
            v?.powerKw?.let { "$it kW" },
            dealer,
            "fit ${Math.round(fit.score * 100)}%",
        ).joinToString(" · ")
        val losses = fit.parts.biggestLosses().joinToString(" · ") { (part, lost) ->
            "${part.wish.label.lowercase()} −$lost" + (part.note?.let { " ($it)" } ?: "")
        }
        return facts + losses.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
    }

    companion object {
        /**
         * Whether this listing is worth a notification now, as opposed to being worth seeing.
         *
         * An auction's price is the highest bid so far, so it says nothing about what the thing
         * will cost — a Crucial module alerted at €10.50 was €21.50 the next day and still rising,
         * and the alert had been about a number that was never an offer. Being told early is no use
         * either: what makes a bid worth knowing is that there is no time left to raise it.
         *
         * The window is the search's own interval rather than a figure picked here. A search that
         * looks every three hours hears about an auction ending within three hours, because that is
         * the last time it will see it before the bidding is over. An auction whose end is unknown
         * cannot be timed, so it waits on the bookmark with everything else.
         */
        internal fun worthInterrupting(
            listing: Listing,
            intervalMinutes: Int,
            now: kotlin.time.Instant = Clock.System.now(),
        ): Boolean {
            if (listing.saleType != SaleType.AUCTION) return true
            val endsAt = listing.auctionEndsAt ?: return false
            return endsAt <= now.plus(intervalMinutes.toDuration(DurationUnit.MINUTES))
        }

        /** Whether a listing satisfies one search's own notification subfilter — narrower than the
         *  search's own criteria, so a match here is always also a match on the search itself.
         *  A plain function of its inputs (no crawl state), so a subfilter's rules can be verified
         *  without spinning up a whole monitor. */
        internal fun matchesSubfilter(
            listing: Listing,
            sf: NotificationSubfilter,
            importSettings: ImportSettings = ImportSettingsStore.current,
        ): Boolean {
            // What it costs to have, not what the market quotes: shipping and the import VAT on a
            // listing from outside the buyer's VAT area are part of the price a limit is about.
            // Otherwise a notification for "under €149" arrives about something that lands at €154.
            val cents = eurCents(listing.landedPrice(importSettings))
            val minEur = sf.minPriceEur
            val maxEur = sf.maxPriceEur
            if (minEur != null && (cents == null || cents < minEur * 100L)) return false
            if (maxEur != null && (cents == null || cents > maxEur * 100L)) return false
            if (!conditionMatches(sf.condition, listing.condition)) return false
            val title = listing.title.lowercase()
            if (sf.mustContainAnyOf.isNotEmpty() && sf.mustContainAnyOf.none { title.contains(it.lowercase()) }) return false
            if (sf.excludeKeywords.any { title.contains(it.lowercase()) }) return false
            return true
        }

        /**
         * Whether a listing's price is what one working thing costs, so it can set or beat a learned
         * price. A thing sold for parts is not the thing, and a row of sizes is priced at the
         * smallest. eBay makes every seller state a condition, so an eBay offer with none was not
         * read rather than not stated: the same for-parts drive read as for parts on eBay.de came
         * without a condition on eBay.es and taught the 1 TB watch a bar of 50 €.
         */
        internal fun pricesTheThing(listing: Listing): Boolean =
            isTheThing(listing) && !(listing.platformId.name.startsWith("EBAY_") && listing.condition == null)

        /**
         * Whether a listing's price is one working thing's, as far as the card tells: not a thing
         * sold for parts, not a row of sizes priced at the smallest, whether the title names them
         * or the card prints a span of prices. A push about any of them is about a price nobody
         * can pay for the thing searched for: a drive titled "2TB" came at 113 € while its 2 TB
         * choice cost 230 $.
         */
        internal fun isTheThing(listing: Listing): Boolean =
            listing.condition != Condition.PARTS_ONLY && listing.priceUpTo == null &&
                !RelevanceFilter.namesSeveralSizes(listing.title)

        /** Whether a find costs less than the cheapest a learning rule saw; true for a rule that never learned. */
        internal fun beatsTheBestSeen(
            listing: Listing,
            sf: NotificationSubfilter,
            importSettings: ImportSettings = ImportSettingsStore.current,
        ): Boolean {
            if (sf.learnUntil == null) return true
            if (!pricesTheThing(listing)) return false
            val best = sf.bestSeenEur ?: return true
            val cents = eurCents(listing.landedPrice(importSettings)) ?: return false
            return cents < best * 100L
        }

        /** The same plain New/Used/Any split the results screen filters by — "NEW", "USED" or null. */
        internal fun conditionMatches(filter: String?, condition: io.github.tieo.arbay.model.Condition?): Boolean =
            when (filter) {
                null -> true
                "NEW" -> condition == io.github.tieo.arbay.model.Condition.NEW
                "USED" -> condition != null && condition != io.github.tieo.arbay.model.Condition.NEW
                else -> true
            }

        /** Price in EUR cents, converting from the listing's own currency so cross-border deals
         *  and subfilters compare on the same footing. */
        internal fun eurCents(money: Money): Long? =
            if (money.currency == Currency.EUR) money.amount
            else runCatching { ExchangeRates.convert(money.amount, money.currency.name, "EUR") }.getOrNull()
    }

    /** The state file, or whatever the two files it replaced still hold. */
    private fun loadState(): SearchState =
        if (stateFile.exists()) stateFile.readStore(log) { json.decodeFromString<SearchState>(it) } ?: SearchState()
        else SearchState(unopened = loadIds(legacyStatusFile), lastNew = loadIds(legacyLastNewFile))

    private fun loadIds(file: File): Map<String, Set<String>> =
        file.readStore(log) { json.decodeFromString<Map<String, Set<String>>>(it) }.orEmpty()

    /** Persist what the watches know, dropping anything belonging to a bookmark that is gone —
     *  a deleted search left its whole listing-id history behind on every previous version. */
    private fun saveStatus() = synchronized(stateFile) {
        val live = productRepo.getAll().map { it.id }.toSet()
        val state = synchronized(stateLock) {
            unopened.keys.retainAll(live)
            lastNew.keys.retainAll(live)
            lastRun.keys.retainAll(live)
            unanswered.keys.retainAll(live)
            // A find time lives as long as its id is still held as new or as the last batch.
            foundAt.keys.retainAll(live)
            foundAt.forEach { (id, times) ->
                val held = unopened[id].orEmpty() + lastNew[id].orEmpty()
                times.keys.retainAll(held)
            }
            // A fit alert's own mark sits beside its search's ids and goes only with the search.
            seen.keys.retainAll { it.removePrefix(FIT_KEY) in live }
            PriceDrops.retain(live)
            SearchState(
                unopened = unopened.mapValues { it.value.toSet() },
                lastNew = lastNew.mapValues { it.value.toSet() },
                lastRunAtMillis = lastRun.toMap(),
                foundAtMillis = foundAt.mapValues { it.value.toMap() },
                unansweredAtLastRun = unanswered.toMap(),
            )
        }
        try {
            stateFile.writeTextAtomically(json.encodeToString(state))
        } catch (e: Exception) {
            log.error("could not write saved-search state: {}", e.message)
        }
    }

    private fun loadSeen(): MutableMap<String, MutableSet<String>> =
        seenFile.readStore(log) { json.decodeFromString<Map<String, Set<String>>>(it) }
            ?.mapValuesTo(HashMap()) { it.value.toMutableSet() }
            ?: HashMap()

    private fun saveSeen() = synchronized(seenFile) {
        val snapshot = synchronized(stateLock) { seen.mapValues { it.value.toList() } }
        try {
            seenFile.writeTextAtomically(json.encodeToString(snapshot))
        } catch (e: Exception) {
            log.error("Could not save saved-search state: {}", e.message)
        }
    }
}

/**
 * Each offer once, best first: a dealer that lists the same van on two markets would otherwise
 * push a new one out of the top twice over. The copy kept is the one that scores best, the one
 * whose market says most about it: a Schrobenhausen van at 88 % on mobile.de ranked as its 79 %
 * Kleinanzeigen copy.
 */
internal fun <F> bestCopyEach(scored: List<Pair<Listing, F>>, score: (F) -> Double): List<Pair<Listing, F>> =
    scored.sortedByDescending { score(it.second) }
        .distinctBy { (l, _) -> listOf(FitScore.priceEur(l).toLong(), l.vehicle?.mileageKm, l.vehicle?.firstRegYear) }

/** The seller's text of [listing], from a page read before or read now. */
private suspend fun readAdText(listing: Listing): String? = try {
    DetailCache.get(listing.id)?.description
        ?: CrawlerRegistry.crawlerFor(listing.platformId)?.fetchDetail(listing)
            ?.also { DetailCache.put(listing.id, it) }?.description
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** What makes two listing ids one offer: an eBay item's number, whichever eBay site it is on. */
/** The reasons that say what an item is, the same in every language its copies are titled in.
 *  Not the word-share ones: a market answering in another language reads off target by its words. */
private val WHAT_THE_ITEM_IS = setOf(
    io.github.tieo.arbay.model.DropReason.ACCESSORY,
    io.github.tieo.arbay.model.DropReason.CONSUMABLE,
    io.github.tieo.arbay.model.DropReason.BUILT_INTO_A_DEVICE,
    io.github.tieo.arbay.model.DropReason.WANTED_AD,
    io.github.tieo.arbay.model.DropReason.RENTAL,
)

internal fun offerKey(listingId: String): String =
    if (listingId.startsWith("EBAY_")) "EBAY:" + listingId.substringAfter(':') else listingId
