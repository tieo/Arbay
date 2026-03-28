package io.github.tieo.arbay.repo

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ListingRepo {
    private val log = LoggerFactory.getLogger(ListingRepo::class.java)
    private val listings = ConcurrentHashMap<String, Listing>()
    private val persistFile = File(System.getProperty("user.home"), ".arbay/sold_listings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val persistPending = AtomicBoolean(false)

    init { loadPersisted() }

    private fun loadPersisted() {
        try {
            if (!persistFile.exists()) return
            val stored = json.decodeFromString<List<Listing>>(persistFile.readText())
            stored.forEach { listings[it.id] = it }
            log.info("Loaded ${stored.size} persisted sold listings")
        } catch (e: Exception) {
            log.warn("Failed to load persisted listings: ${e.message}")
        }
    }

    private fun schedulePersist() {
        if (persistPending.compareAndSet(false, true)) {
            Thread {
                Thread.sleep(2000)
                persistPending.set(false)
                try {
                    persistFile.parentFile.mkdirs()
                    val sold = listings.values.filter { it.sold }
                    persistFile.writeText(json.encodeToString(sold))
                } catch (e: Exception) {
                    log.warn("Failed to persist sold listings: ${e.message}")
                }
            }.also { it.isDaemon = true }.start()
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

    fun getByExternalId(platformId: PlatformId, externalId: String): Listing? {
        return listings.values.find { it.platformId == platformId && it.externalId == externalId }
    }

    fun upsert(listing: Listing): Listing {
        listings[listing.id] = listing
        if (listing.sold) schedulePersist()
        return listing
    }

    fun upsertBatch(batch: List<Listing>): Int {
        batch.forEach { listings[it.id] = it }
        if (batch.any { it.sold }) schedulePersist()
        return batch.size
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
