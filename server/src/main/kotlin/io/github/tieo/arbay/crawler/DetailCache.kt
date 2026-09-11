package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.ListingDetail
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent cache of what a detail page said, keyed by listing id: the specs, the seller's own
 * description and where the thing is. None of that changes while an ad is up, so it is cached for
 * a long TTL and reused across searches and restarts — a detail page is fetched at most once,
 * which is what keeps detail enrichment block-safe.
 */
object DetailCache {
    private val log = LoggerFactory.getLogger(DetailCache::class.java)
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days

    @Serializable
    private data class Entry(val detail: ListingDetail, val storedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val persistFile = File(System.getProperty("user.home"), ".arbay/detail_cache.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val persistPending = AtomicBoolean(false)

    init { load() }

    private fun load() {
        try {
            if (!persistFile.exists()) return
            val now = Clock.System.now().toEpochMilliseconds()
            json.decodeFromString<Map<String, Entry>>(persistFile.readText())
                .filterValues { now - it.storedAtMs < TTL_MS }
                .forEach { (k, v) -> entries[k] = v }
            log.info("Loaded ${entries.size} cached detail pages")
        } catch (e: Exception) {
            log.warn("Failed to load detail cache: ${e.message}")
        }
    }

    fun get(listingId: String): ListingDetail? {
        val e = entries[listingId] ?: return null
        if (Clock.System.now().toEpochMilliseconds() - e.storedAtMs > TTL_MS) {
            entries.remove(listingId)
            return null
        }
        return e.detail
    }

    fun put(listingId: String, detail: ListingDetail) {
        entries[listingId] = Entry(detail, Clock.System.now().toEpochMilliseconds())
        schedulePersist()
    }

    private fun schedulePersist() {
        if (persistPending.compareAndSet(false, true)) {
            Thread {
                Thread.sleep(3000)
                persistPending.set(false)
                try {
                    persistFile.parentFile.mkdirs()
                    persistFile.writeText(json.encodeToString(entries.toMap()))
                } catch (e: Exception) {
                    log.warn("Failed to persist detail cache: ${e.message}")
                }
            }.also { it.isDaemon = true }.start()
        }
    }
}
