package io.github.tieo.arbay.classifier

import io.github.tieo.arbay.model.Listing
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class TrackedItem(
    val listingId: String,
    val title: String,
    val url: String,
    val imageUrl: String? = null,
    val locationText: String? = null,
    val description: String? = null,
    val relevanceScore: Double? = null,
    val firstSeen: Instant,
    val lastSeen: Instant,
)

/**
 * Persistent store for all scraped free items.
 * Tracks first-seen/last-seen timestamps to detect new arrivals.
 */
object FreeItemStore {

    private val log = LoggerFactory.getLogger(FreeItemStore::class.java)
    private val storeFile = File(System.getProperty("user.home"), ".arbay/free_item_store.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val items = mutableMapOf<String, TrackedItem>()
    private var lastCheckTimestamp: Instant = Instant.DISTANT_PAST

    init {
        load()
    }

    /** Record a batch of scraped listings. Returns IDs of items seen for the first time. */
    fun trackBatch(listings: List<Listing>): List<String> {
        val now = Clock.System.now()
        val newIds = mutableListOf<String>()

        synchronized(items) {
            for (listing in listings) {
                val existing = items[listing.id]
                if (existing == null) {
                    newIds.add(listing.id)
                    items[listing.id] = TrackedItem(
                        listingId = listing.id,
                        title = listing.title,
                        url = listing.url,
                        imageUrl = listing.imageUrls.firstOrNull { it.startsWith("http") },
                        locationText = listing.location?.let { loc ->
                            loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
                        },
                        description = listing.description,
                        relevanceScore = listing.relevanceScore,
                        firstSeen = now,
                        lastSeen = now,
                    )
                } else {
                    // Update last seen and score
                    items[listing.id] = existing.copy(
                        lastSeen = now,
                        relevanceScore = listing.relevanceScore ?: existing.relevanceScore,
                    )
                }
            }
        }

        if (newIds.isNotEmpty()) {
            log.info("Tracked {} new items out of {} total", newIds.size, listings.size)
            save()
        }

        return newIds
    }

    /** Get items first seen since the given timestamp, sorted by relevance score descending. */
    fun newItemsSince(since: Instant): List<TrackedItem> = synchronized(items) {
        items.values
            .filter { it.firstSeen > since }
            .sortedByDescending { it.relevanceScore ?: 0.0 }
    }

    /** Mark the current time as "last checked" and return items new since previous check. */
    fun checkNewItems(): List<TrackedItem> {
        val since = lastCheckTimestamp
        lastCheckTimestamp = Clock.System.now()
        return newItemsSince(since)
    }

    /** Get high-confidence new items (score >= threshold) since last check. */
    fun highConfidenceNewItems(threshold: Double = 0.65): List<TrackedItem> {
        return checkNewItems().filter { (it.relevanceScore ?: 0.0) >= threshold }
    }

    /** Total tracked items count. */
    fun totalCount(): Int = synchronized(items) { items.size }

    /** Get all tracked items, most recent first. */
    fun allItems(): List<TrackedItem> = synchronized(items) {
        items.values.sortedByDescending { it.lastSeen }.toList()
    }

    /** Prune items not seen in the last N days. */
    fun prune(maxAgeDays: Int = 30) {
        val cutoff = Clock.System.now() - kotlin.time.Duration.parse("${maxAgeDays}d")
        val removed = synchronized(items) {
            items.entries.removeAll { it.value.lastSeen < cutoff }
        }
        if (removed) save()
    }

    private fun load() {
        if (!storeFile.exists()) return
        try {
            val loaded = json.decodeFromString<List<TrackedItem>>(storeFile.readText())
            synchronized(items) {
                loaded.forEach { items[it.listingId] = it }
            }
            log.info("Loaded {} tracked free items", loaded.size)
        } catch (e: Exception) {
            log.warn("Could not load free item store: ${e.message}")
        }
    }

    private fun save() {
        try {
            storeFile.parentFile.mkdirs()
            val copy = synchronized(items) { items.values.toList() }
            storeFile.writeText(json.encodeToString(copy))
        } catch (e: Exception) {
            log.warn("Could not save free item store: ${e.message}")
        }
    }
}
