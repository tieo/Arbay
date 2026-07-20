package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.PlatformId
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
class SavedSearchMonitor(private val productRepo: ProductRepo) {

    private val log = LoggerFactory.getLogger(SavedSearchMonitor::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val enabled = System.getenv("ARBAY_SAVED_SEARCH_UPDATES")?.equals("on", true) == true
    private val intervalMs =
        (System.getenv("ARBAY_SAVED_SEARCH_INTERVAL_MIN")?.toLongOrNull() ?: 360L).coerceAtLeast(60L) * 60_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val seenFile = File(System.getProperty("user.home"), ".arbay/saved_search_seen.json")
    // Listing ids already reported, keyed by saved-search id, so only genuinely new stock alerts.
    private val seen: MutableMap<String, MutableSet<String>> = loadSeen()

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

            val found = LinkedHashMap<String, String>() // listing id -> title
            for (platformId in platforms) {
                if (BlockCooldown.isCoolingDown(platformId)) continue
                val crawler = CrawlerRegistry.crawlerFor(platformId) ?: continue
                val query = product.searchQuery.copy(platforms = listOf(platformId))
                val results = try {
                    withTimeout(120_000L) { crawler.trackedSearch(query) }
                } catch (e: Exception) {
                    log.debug("saved-search {} on {} failed: {}", product.id, platformId, e.message?.take(60))
                    continue
                }
                RelevanceFilter.filter(results, query).forEach { found[it.id] = it.title }
            }

            val known = seen.getOrPut(product.id) { mutableSetOf() }
            val fresh = found.keys.filter { it !in known }
            known.addAll(found.keys)

            if (fresh.isNotEmpty() && !silent) {
                val examples = fresh.take(3).joinToString("; ") { found[it].orEmpty().take(50) }
                BlockAlerter.notify(
                    title = "Arbay: ${fresh.size} new for ${product.name}",
                    message = examples,
                )
            }
        }
        saveSeen()
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
