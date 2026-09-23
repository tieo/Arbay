package io.github.tieo.arbay.repo

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.tidyTitle
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class ListingRepo {
    private val log = LoggerFactory.getLogger(ListingRepo::class.java)
    private val listings = ConcurrentHashMap<String, Listing>()
    private val persistFile = DataDir.file("sold_listings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val persistPending = AtomicBoolean(false)

    // One thread does every write, so two writes of the file never run at once.
    private val writer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "sold-listings-writer").apply { isDaemon = true }
    }

    init { loadPersisted() }

    // A file that cannot be read is moved aside by readStore before anything is written, so the
    // sold listings it held survive for whoever reads the log.
    private fun loadPersisted() {
        val stored = persistFile.readStore(log) { json.decodeFromString<List<Listing>>(it) } ?: return
        stored.forEach { listings[it.id] = it }
        log.info("Loaded ${stored.size} persisted sold listings")
    }

    // Writes are gathered for a couple of seconds, so a batch of sold listings writes once.
    private fun schedulePersist() {
        if (persistPending.compareAndSet(false, true)) {
            writer.schedule({
                persistPending.set(false)
                try {
                    val sold = listings.values.filter { it.sold }
                    persistFile.writeTextAtomically(json.encodeToString(sold))
                } catch (e: Exception) {
                    log.error("Failed to persist sold listings: {}", e.message)
                }
            }, 2, TimeUnit.SECONDS)
        }
    }

    fun getAll(
        platformId: PlatformId? = null,
        sold: Boolean? = null,
        limit: Int = 50,
        offset: Int = 0,
    ): List<Listing> {
        return listings.values
            .asSequence()
            .filter { platformId == null || it.platformId == platformId }
            .filter { sold == null || it.sold == sold }
            .sortedByDescending { it.scrapedAt }
            .drop(offset)
            .take(limit)
            .toList()
    }

    fun getById(id: String): Listing? = listings[id]

    /** Share of stored listings whose title contains [term], the background a search's own results
     *  are judged against: a word this common everywhere says nothing about the thing searched for.
     *  0 when nothing has been crawled yet, which leaves every word looking distinctive. */
    fun titleShareOfCorpus(term: String): Double {
        val all = listings.values
        if (all.isEmpty()) return 0.0
        return all.count { it.title.contains(term, ignoreCase = true) }.toDouble() / all.size
    }

    fun upsert(listing: Listing): Listing {
        val clean = listing.tidied()
        listings[clean.id] = clean
        if (clean.sold) schedulePersist()
        ListingArchive.archiveAsync(clean)
        return clean
    }

    fun upsertBatch(batch: List<Listing>): Int {
        batch.forEach { val clean = it.tidied(); listings[clean.id] = clean; ListingArchive.archiveAsync(clean) }
        if (batch.any { it.sold }) schedulePersist()
        return batch.size
    }

    /** A market's HTML leaks into its titles: eBay's inline image arrives as U+FFFC and draws as a
     *  box reading OBJ. Cleaned once here rather than at every place a title is read. */
    private fun Listing.tidied(): Listing {
        val clean = title.tidyTitle()
        return if (clean == title) this else copy(title = clean)
    }

    fun search(query: String, limit: Int = 50): List<Listing> {
        val lower = query.lowercase()
        return listings.values
            .filter { it.title.lowercase().contains(lower) }
            .sortedByDescending { it.scrapedAt }
            .take(limit)
    }

    fun getPriceHistory(platformId: PlatformId?, titleQuery: String): List<Listing> {
        val lower = titleQuery.lowercase()
        return listings.values
            .filter { it.sold && it.title.lowercase().contains(lower) }
            .filter { platformId == null || it.platformId == platformId }
            .sortedByDescending { it.soldDate ?: it.scrapedAt }
    }
}
