package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
    private val persistFile = DataDir.file("detail_cache.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val persistPending = AtomicBoolean(false)

    // Past this many pages the oldest are dropped, so the file stays a size that is cheap to
    // rewrite after every new page.
    private const val MAX_ENTRIES = 10_000

    // One thread does every write, so two writes of the file never run at once.
    private val writer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "detail-cache-writer").apply { isDaemon = true }
    }

    init { load() }

    private fun load() {
        val now = Clock.System.now().toEpochMilliseconds()
        persistFile.readStore(log) { json.decodeFromString<Map<String, Entry>>(it) }
            ?.filterValues { now - it.storedAtMs < TTL_MS }
            ?.forEach { (k, v) -> entries[k] = v }
        log.info("Loaded ${entries.size} cached detail pages")
    }

    /**
     * When a market's pages started being read the way they are now. A page cached before then
     * holds what the older reading made of it, so it counts as unread and is read again: a cached
     * mobile.de page kept its equipment list run together ("NormaldachKraftstofftank") for the
     * cache's thirty days after the list was read one item per line, and the normal roof it states
     * kept a van in a search for high roofs.
     */
    private val readingChangedAtMs: Map<String, Long> = mapOf(
        "MOBILE_DE" to kotlin.time.Instant.parse("2026-10-29T08:17:00Z").toEpochMilliseconds(),
    )

    fun get(listingId: String): ListingDetail? {
        val e = entries[listingId] ?: return null
        val readSince = readingChangedAtMs[listingId.substringBefore(':')] ?: 0L
        if (Clock.System.now().toEpochMilliseconds() - e.storedAtMs > TTL_MS || e.storedAtMs < readSince) {
            entries.remove(listingId)
            return null
        }
        return e.detail
    }

    /** How long ago [listingId]'s page was read, in milliseconds; null where it was not. */
    fun ageMs(listingId: String): Long? =
        entries[listingId]?.let { Clock.System.now().toEpochMilliseconds() - it.storedAtMs }

    fun remove(listingId: String) {
        if (entries.remove(listingId) != null) schedulePersist()
    }

    fun put(listingId: String, detail: ListingDetail) {
        entries[listingId] = Entry(detail, Clock.System.now().toEpochMilliseconds())
        schedulePersist()
    }

    // Writes are gathered for a few seconds, so a crawl that fetches forty detail pages writes
    // the file once rather than forty times.
    private fun schedulePersist() {
        if (persistPending.compareAndSet(false, true)) {
            writer.schedule({
                persistPending.set(false)
                persist()
            }, 3, TimeUnit.SECONDS)
        }
    }

    private fun persist() {
        val now = Clock.System.now().toEpochMilliseconds()
        entries.entries.removeIf { now - it.value.storedAtMs >= TTL_MS }
        val overflow = entries.size - MAX_ENTRIES
        if (overflow > 0) {
            entries.entries.sortedBy { it.value.storedAtMs }.take(overflow).forEach { entries.remove(it.key) }
        }
        try {
            persistFile.writeTextAtomically(json.encodeToString(entries.toMap()))
        } catch (e: Exception) {
            log.error("Failed to persist detail cache: {}", e.message)
        }
    }
}
