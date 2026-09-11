package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.ImportSettings
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.landedPrice
import io.github.tieo.arbay.model.NotificationSubfilter
import io.github.tieo.arbay.model.SavedSearchStatus
import io.github.tieo.arbay.model.SubfilterMatch
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.model.displayName
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.repo.ImportSettingsStore
import io.github.tieo.arbay.repo.ListingArchive
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.datetime.Clock
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import kotlinx.serialization.Serializable
import io.github.tieo.arbay.repo.ProductRepo
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

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
) {

    private val log = LoggerFactory.getLogger(SavedSearchMonitor::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    // How often a search's own interval is checked, not how often a search itself is re-crawled.
    private val TICK_MS = 60_000L

    // A floor under whatever interval a search is given, so a mistyped "5" does not turn into a
    // five-minute crawl loop — the flag risk the anti-block work manages is per-crawl, not per-search.
    private val MIN_INTERVAL_MIN = 30

    // Subfilter matches found since the phone last polled. Held rather than sent: the notification
    // is raised on the device, so this waits for the device to ask.
    private val pendingSubfilterMatches = mutableListOf<SubfilterMatch>()

    /** Subfilter matches found since the last call, handed to the phone that will raise the
     *  notifications. */
    fun drainSubfilterMatches(): List<SubfilterMatch> = synchronized(pendingSubfilterMatches) {
        pendingSubfilterMatches.toList().also { pendingSubfilterMatches.clear() }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val seenFile = File(System.getProperty("user.home"), ".arbay/saved_search_seen.json")
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
     * blocked.
     */
    @Serializable
    private data class SearchState(
        val unopened: Map<String, Set<String>> = emptyMap(),
        val lastNew: Map<String, Set<String>> = emptyMap(),
        val lastRunAtMillis: Map<String, Long> = emptyMap(),
    )

    private val stateFile = File(System.getProperty("user.home"), ".arbay/saved_search_state.json")
    private val legacyStatusFile = File(System.getProperty("user.home"), ".arbay/saved_search_status.json")
    private val legacyLastNewFile = File(System.getProperty("user.home"), ".arbay/saved_search_last_new.json")

    private val unopened: MutableMap<String, MutableSet<String>>
    private val lastNew: MutableMap<String, MutableSet<String>>
    private val lastRun: MutableMap<String, Long>

    init {
        val state = loadState()
        unopened = state.unopened.mapValuesTo(HashMap()) { it.value.toMutableSet() }
        lastNew = state.lastNew.mapValuesTo(HashMap()) { it.value.toMutableSet() }
        lastRun = HashMap(state.lastRunAtMillis)
    }

    /** What every saved search has been doing, for the app's list of them. */
    fun statuses(): List<SavedSearchStatus> = productRepo.getAll().map { product ->
        SavedSearchStatus(
            productId = product.id,
            watched = product.autoFetch.enabled,
            lastRunAtMillis = lastRun[product.id],
            newSinceOpened = unopened[product.id]?.size ?: 0,
            newListingIds = unopened[product.id]?.toList().orEmpty(),
        )
    }

    /** Called when a saved search is opened: what was waiting has now been seen. */
    fun markOpened(productId: String) {
        unopened.remove(productId)
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
        val ids = unopened[productId]?.takeIf { it.isNotEmpty() } ?: lastNew[productId].orEmpty()
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
            val last = lastRun[product.id] ?: 0L
            if (now - last < intervalMs) continue
            try {
                runProduct(product)
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
        val silent = product.id !in seen
        val platforms = product.searchQuery.platforms
            .filter { CrawlerRegistry.crawlerFor(it) != null }
            .ifEmpty { return }

        val found = LinkedHashMap<String, Listing>() // listing id -> listing
        for (platformId in platforms) {
            if (BlockCooldown.isCoolingDown(platformId)) continue
            val crawler = CrawlerRegistry.crawlerFor(platformId) ?: continue
            // Asked exactly as the search asks when it is opened by hand, including a term the
            // searcher accepted for this market's language. A watch that asked something else
            // would notify about a different set of listings than the screen shows.
            val query = localizedQuery(product.searchQuery.copy(platforms = listOf(platformId)), platformId)
            val results = try {
                withTimeout(120_000L) { crawler.trackedSearch(query) { term -> listingRepo.titleShareOfCorpus(term) } }
            } catch (e: Exception) {
                log.debug("saved-search {} on {} failed: {}", product.id, platformId, e.message?.take(60))
                continue
            }
            RelevanceFilter.filter(results, query).forEach { found[it.id] = it }
        }

        lastRun[product.id] = System.currentTimeMillis()

        // Every market refused or failed. Recording that as this search's first, silent run would
        // spend the one chance to seed quietly on nothing, and the next run that does reach a
        // market would then announce its entire result set as new stock. Blocks are routine, so
        // this is the common case, not a corner: leave [seen] untouched and try again next tick.
        if (found.isEmpty()) {
            saveStatus()
            return
        }

        val known = seen.getOrPut(product.id) { mutableSetOf() }
        val fresh = found.keys.filter { it !in known }
        known.addAll(found.keys)
        if (fresh.isNotEmpty() && !silent) {
            unopened.getOrPut(product.id) { mutableSetOf() }.addAll(fresh)
            lastNew[product.id] = fresh.toMutableSet()
            saveStatus()
            // Stored as found, so the app can show this batch later without asking the platform
            // again — which by then may no longer have it. Archiving mirrors the images too.
            listingRepo.upsertBatch(fresh.mapNotNull { found[it] })
        }
        if (fresh.isEmpty() || silent) { saveSeen(); return }

        val freshListings = fresh.mapNotNull { found[it] }

        // Named notification subfilters someone set on this search specifically — a listing can
        // match more than one, and each match is worth its own notification since each names a
        // different reason the person cared enough to ask for it.
        val subfilterMatches = product.notificationSubfilters
            .filter { it.enabled }
            .flatMap { sf ->
                freshListings
                    .filter {
                        // A notification is an interruption, so it has to be about the thing that
                        // was searched for, not merely about something the market chose to answer
                        // with. Where the search's own words are written by nobody, everything a
                        // market sends is kept for the screen — and none of it is worth waking
                        // someone for.
                        RelevanceFilter.carriesAWordOfTheSearch(it, product.searchQuery) &&
                            matchesSubfilter(it, sf) && worthInterrupting(
                            it,
                            product.autoFetch.intervalMinutes.coerceAtLeast(MIN_INTERVAL_MIN),
                        )
                    }
                    .map { sf to it }
            }
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

        log.info(
            "saved-search {}: {} new, {} subfilter matches",
            product.name, fresh.size, subfilterMatches.size,
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
            now: kotlinx.datetime.Instant = Clock.System.now(),
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
    private fun loadState(): SearchState = try {
        if (stateFile.exists()) json.decodeFromString<SearchState>(stateFile.readText())
        else SearchState(unopened = loadIds(legacyStatusFile), lastNew = loadIds(legacyLastNewFile))
    } catch (e: Exception) {
        log.warn("Could not read saved-search state, starting from empty: {}", e.message)
        SearchState()
    }

    private fun loadIds(file: File): Map<String, Set<String>> = try {
        if (file.exists()) json.decodeFromString<Map<String, Set<String>>>(file.readText()) else emptyMap()
    } catch (_: Exception) { emptyMap() }

    /** Persist what the watches know, dropping anything belonging to a bookmark that is gone —
     *  a deleted search left its whole listing-id history behind on every previous version. */
    private fun saveStatus() {
        val live = productRepo.getAll().map { it.id }.toSet()
        unopened.keys.retainAll(live)
        lastNew.keys.retainAll(live)
        lastRun.keys.retainAll(live)
        seen.keys.retainAll(live)
        try {
            stateFile.writeTextAtomically(
                json.encodeToString(
                    SearchState(
                        unopened = unopened.mapValues { it.value.toSet() },
                        lastNew = lastNew.mapValues { it.value.toSet() },
                        lastRunAtMillis = lastRun.toMap(),
                    ),
                ),
            )
        } catch (e: Exception) {
            log.debug("could not write saved-search state: {}", e.message)
        }
    }

    private fun loadSeen(): MutableMap<String, MutableSet<String>> = try {
        if (seenFile.exists()) {
            json.decodeFromString<Map<String, Set<String>>>(seenFile.readText())
                .mapValuesTo(HashMap()) { it.value.toMutableSet() }
        } else HashMap()
    } catch (e: Exception) {
        log.warn("Could not load saved-search state: {}", e.message)
        HashMap()
    }

    private fun saveSeen() {
        try {
            seenFile.writeTextAtomically(json.encodeToString(seen.mapValues { it.value.toList() }))
        } catch (e: Exception) {
            log.warn("Could not save saved-search state: {}", e.message)
        }
    }
}
