package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.classifier.FreeItemMonitor
import io.github.tieo.arbay.model.DealMatch
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.NotificationSubfilter
import io.github.tieo.arbay.model.SavedSearchStatus
import io.github.tieo.arbay.model.SubfilterMatch
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.model.displayName
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.repo.ListingArchive
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.ProductRepo
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Re-runs a saved search (TrackedProduct) on the schedule set on that search, so a bookmark keeps
 * finding new stock without the user re-searching. What it finds shows on the saved search itself;
 * a listing matching one of the search's own notification subfilters, or priced under the search's
 * median, is held for the phone to notify about.
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

    // Opportunistic-buying threshold: a fresh listing priced at or below this fraction of the
    // search's median counts as a deal worth interrupting for. The fraction and the switch are the
    // notification settings the phone shows, so what the app promises is what runs here. Needs at
    // least dealMinSample priced listings for the median to mean anything.
    private val dealMinSample = 5

    // Deals found since the phone last polled. Held rather than sent: the notification is raised on
    // the device, so this waits for the device to ask.
    private val pendingDeals = mutableListOf<DealMatch>()

    // Subfilter matches found since the phone last polled, same reasoning as pendingDeals.
    private val pendingSubfilterMatches = mutableListOf<SubfilterMatch>()

    /** Deals found since the last call, handed to the phone that will raise the notifications. */
    fun drainDeals(): List<DealMatch> = synchronized(pendingDeals) {
        pendingDeals.toList().also { pendingDeals.clear() }
    }

    /** Subfilter matches found since the last call, handed to the phone that will raise the
     *  notifications. */
    fun drainSubfilterMatches(): List<SubfilterMatch> = synchronized(pendingSubfilterMatches) {
        pendingSubfilterMatches.toList().also { pendingSubfilterMatches.clear() }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val seenFile = File(System.getProperty("user.home"), ".arbay/saved_search_seen.json")
    // Listing ids already reported, keyed by saved-search id, so only genuinely new stock alerts.
    private val seen: MutableMap<String, MutableSet<String>> = loadSeen()

    // What each saved search has found since someone last opened it, and when it last ran. Held
    // here because this is what knows; the app reads it so a bookmark can say what is waiting.
    private val statusFile = File(System.getProperty("user.home"), ".arbay/saved_search_status.json")
    private val unopened: MutableMap<String, MutableSet<String>> = loadIds(statusFile)
    private val lastRun: MutableMap<String, Long> = mutableMapOf()

    // What the most recent run that found anything found, kept after the search is opened. Opening
    // a search clears what is unseen, and that used to take with it the only record of which
    // listings the watch had turned up — so "show me what you found" had nothing to show the
    // second time it was asked.
    private val lastNewFile = File(System.getProperty("user.home"), ".arbay/saved_search_last_new.json")
    private val lastNew: MutableMap<String, MutableSet<String>> = loadIds(lastNewFile)

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
        return ids.mapNotNull { listingRepo.getById(it) ?: ListingArchive.get(it) }
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
            val query = product.searchQuery.copy(platforms = listOf(platformId))
            val results = try {
                withTimeout(120_000L) { crawler.trackedSearch(query) { term -> listingRepo.titleShareOfCorpus(term) } }
            } catch (e: Exception) {
                log.debug("saved-search {} on {} failed: {}", product.id, platformId, e.message?.take(60))
                continue
            }
            RelevanceFilter.filter(results, query).forEach { found[it.id] = it }
        }

        val known = seen.getOrPut(product.id) { mutableSetOf() }
        val fresh = found.keys.filter { it !in known }
        known.addAll(found.keys)
        lastRun[product.id] = System.currentTimeMillis()
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

        // Opportunistic buying: a fresh listing priced well below the search's typical price
        // goes to the phone. New stock does not. Home shows what a saved search has found since
        // it was last opened, so a notification saying the same thing every six hours is a
        // second channel telling you what the first one already does.
        val alerts = FreeItemMonitor.settings
        val dealRatio = alerts.dealUnderMedianPct / 100.0
        val median = medianEur(found.values)
        val deals = if (median != null && alerts.dealAlerts) {
            freshListings
                .mapNotNull { l -> eurCents(l.price)?.let { l to it } }
                .filter { (_, cents) -> cents <= median * dealRatio }
                .sortedBy { it.second }
        } else emptyList()

        if (deals.isNotEmpty() && median != null) {
            synchronized(pendingDeals) {
                deals.take(5).forEach { (l, cents) ->
                    pendingDeals += DealMatch(
                        listingId = l.id,
                        searchName = product.name,
                        title = l.title,
                        url = l.url,
                        priceText = "€${cents / 100}",
                        underMedianPct = (100 - cents * 100 / median).toInt(),
                        locationText = l.location?.let { it.city ?: it.country },
                    )
                }
            }
        }

        // Named notification subfilters someone set on this search specifically — a listing can
        // match more than one, and each match is worth its own notification since each names a
        // different reason the person cared enough to ask for it.
        val subfilterMatches = product.notificationSubfilters
            .filter { it.enabled }
            .flatMap { sf -> freshListings.filter { matchesSubfilter(it, sf) }.map { sf to it } }
        if (subfilterMatches.isNotEmpty()) {
            synchronized(pendingSubfilterMatches) {
                subfilterMatches.take(10).forEach { (sf, l) ->
                    pendingSubfilterMatches += SubfilterMatch(
                        listingId = l.id,
                        searchName = product.name,
                        subfilterName = sf.displayName,
                        title = l.title,
                        url = l.url,
                        priceText = eurCents(l.price)?.let { "€${it / 100}" },
                        locationText = l.location?.let { it.city ?: it.country },
                    )
                }
            }
        }

        log.info(
            "saved-search {}: {} new, {} under median, {} subfilter matches",
            product.name, fresh.size, deals.size, subfilterMatches.size,
        )
        saveSeen()
    }

    /** Median listing price in EUR cents, or null if too few priced listings for a stable baseline. */
    private fun medianEur(listings: Collection<Listing>): Long? {
        val prices = listings.mapNotNull { eurCents(it.price) }.filter { it > 0 }.sorted()
        if (prices.size < dealMinSample) return null
        return prices[prices.size / 2]
    }

    companion object {
        /** Whether a listing satisfies one search's own notification subfilter — narrower than the
         *  search's own criteria, so a match here is always also a match on the search itself.
         *  A plain function of its inputs (no crawl state), so a subfilter's rules can be verified
         *  without spinning up a whole monitor. */
        internal fun matchesSubfilter(listing: Listing, sf: NotificationSubfilter): Boolean {
            val cents = eurCents(listing.price)
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

    private fun loadIds(file: File): MutableMap<String, MutableSet<String>> = try {
        if (file.exists()) {
            json.decodeFromString<Map<String, Set<String>>>(file.readText())
                .mapValuesTo(HashMap()) { it.value.toMutableSet() }
        } else HashMap()
    } catch (_: Exception) { HashMap() }

    private fun saveStatus() {
        try {
            statusFile.parentFile.mkdirs()
            statusFile.writeText(json.encodeToString(unopened.mapValues { it.value.toSet() }))
            lastNewFile.writeText(json.encodeToString(lastNew.mapValues { it.value.toSet() }))
        } catch (e: Exception) {
            log.debug("could not write saved-search status: {}", e.message)
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
            seenFile.parentFile.mkdirs()
            seenFile.writeText(json.encodeToString(seen.mapValues { it.value.toList() }))
        } catch (e: Exception) {
            log.warn("Could not save saved-search state: {}", e.message)
        }
    }
}
