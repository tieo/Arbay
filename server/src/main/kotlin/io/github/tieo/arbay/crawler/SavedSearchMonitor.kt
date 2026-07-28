package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SavedSearchStatus
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.ProductRepo
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Re-runs each saved search (TrackedProduct) on a slow schedule and pushes an ntfy alert when a
 * listing appears that was not seen on a previous run, so a bookmark keeps finding new stock
 * without the user re-searching.
 *
 * Off by default. Recurring crawls raise the flag risk the anti-block work manages, so this only
 * runs when ARBAY_SAVED_SEARCH_UPDATES=on is set. When it runs it reuses the same throttle,
 * per-platform pacing, block-cooldown and request budget as an interactive search (via
 * trackedSearch), and defaults to a long interval so it stays a background trickle, not a burst.
 */
class SavedSearchMonitor(
    private val productRepo: ProductRepo,
    private val listingRepo: ListingRepo,
) {

    private val log = LoggerFactory.getLogger(SavedSearchMonitor::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val enabled = System.getenv("ARBAY_SAVED_SEARCH_UPDATES")?.equals("on", true) == true
    private val intervalMs =
        (System.getenv("ARBAY_SAVED_SEARCH_INTERVAL_MIN")?.toLongOrNull() ?: 360L).coerceAtLeast(60L) * 60_000L

    // Opportunistic-buying threshold: a fresh listing priced at or below this fraction of the
    // search's median counts as a deal worth a distinct alert. Needs at least DEAL_MIN_SAMPLE
    // priced listings for the median to mean anything.
    private val dealRatio = (System.getenv("ARBAY_DEAL_RATIO")?.toDoubleOrNull() ?: 0.75).coerceIn(0.3, 0.99)
    private val dealMinSample = 5

    private val json = Json { ignoreUnknownKeys = true }
    private val seenFile = File(System.getProperty("user.home"), ".arbay/saved_search_seen.json")
    // Listing ids already reported, keyed by saved-search id, so only genuinely new stock alerts.
    private val seen: MutableMap<String, MutableSet<String>> = loadSeen()

    // What each saved search has found since someone last opened it, and when it last ran. Held
    // here because this is what knows; the app reads it so a bookmark can say what is waiting.
    private val statusFile = File(System.getProperty("user.home"), ".arbay/saved_search_status.json")
    private val unopened: MutableMap<String, MutableSet<String>> = loadStatus()
    private val lastRun: MutableMap<String, Long> = mutableMapOf()

    /** What every saved search has been doing, for the app's list of them. */
    fun statuses(): List<SavedSearchStatus> = productRepo.getAll().map { product ->
        SavedSearchStatus(
            productId = product.id,
            watched = enabled,
            lastRunAtMillis = lastRun[product.id],
            newSinceOpened = unopened[product.id]?.size ?: 0,
        )
    }

    /** Called when a saved search is opened: what was waiting has now been seen. */
    fun markOpened(productId: String) {
        unopened.remove(productId)
        saveStatus()
    }

    fun start() {
        if (!enabled) {
            log.info("Saved-search updates off (set ARBAY_SAVED_SEARCH_UPDATES=on to enable).")
            return
        }
        if (job?.isActive == true) return
        log.info("Saved-search updater on, every {} min", intervalMs / 60_000)
        job = scope.launch {
            // A first run seeds the seen-set silently, so the user is not alerted for the whole
            // existing backlog the moment the feature is turned on.
            var first = true
            while (isActive) {
                try {
                    runOnce(silent = first)
                    first = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("Saved-search run failed: {}", e.message)
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One pass over every saved search. @param silent seeds the seen-set without alerting. */
    private suspend fun runOnce(silent: Boolean) {
        for (product in productRepo.getAll()) {
            val platforms = product.searchQuery.platforms
                .filter { CrawlerRegistry.crawlerFor(it) != null }
                .ifEmpty { continue }

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
                saveStatus()
            }
            if (fresh.isEmpty() || silent) { saveSeen(); continue }

            // Opportunistic buying: a fresh listing priced well below the search's typical price is a
            // deal — a distinct, higher-value alert than mere new stock.
            val median = medianEur(found.values)
            val deals = if (median != null) {
                fresh.mapNotNull { found[it] }
                    .mapNotNull { l -> eurCents(l.price)?.let { l to it } }
                    .filter { (_, cents) -> cents <= median * dealRatio }
                    .sortedBy { it.second }
            } else emptyList()

            if (deals.isNotEmpty() && median != null) {
                val best = deals.take(3).joinToString("; ") { (l, cents) ->
                    val pct = (100 - cents * 100 / median)
                    val where = l.location?.let { it.city ?: it.country }?.let { " ($it)" } ?: ""
                    "${l.title.take(40)} €${cents / 100} −$pct%$where"
                }
                BlockAlerter.notify(
                    title = "Arbay deal: ${deals.size} under market for ${product.name}",
                    message = best,
                )
            } else {
                // New stock is not a push. Home shows what a saved search has found since it was
                // last opened, so a notification saying the same thing every six hours is a second
                // channel telling you what the first one already does.
                log.info("saved-search {}: {} new, shown on the saved search itself", product.name, fresh.size)
            }
        }
        saveSeen()
    }

    /** Median listing price in EUR cents, or null if too few priced listings for a stable baseline. */
    private fun medianEur(listings: Collection<Listing>): Long? {
        val prices = listings.mapNotNull { eurCents(it.price) }.filter { it > 0 }.sorted()
        if (prices.size < dealMinSample) return null
        return prices[prices.size / 2]
    }

    /** Price in EUR cents, converting from the listing's own currency so cross-border deals compare. */
    private fun eurCents(money: Money): Long? =
        if (money.currency == Currency.EUR) money.amount
        else runCatching { ExchangeRates.convert(money.amount, money.currency.name, "EUR") }.getOrNull()

    private fun loadStatus(): MutableMap<String, MutableSet<String>> = try {
        if (statusFile.exists()) {
            json.decodeFromString<Map<String, Set<String>>>(statusFile.readText())
                .mapValuesTo(HashMap()) { it.value.toMutableSet() }
        } else HashMap()
    } catch (_: Exception) { HashMap() }

    private fun saveStatus() {
        try {
            statusFile.parentFile.mkdirs()
            statusFile.writeText(json.encodeToString(unopened.mapValues { it.value.toSet() }))
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
