package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import kotlinx.datetime.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived per-platform result cache. A user tweaking filters and re-running the same
 * search would otherwise re-crawl every marketplace each time, which is the request pattern
 * most likely to get an IP flagged. A hit serves the stored listings and fires zero outbound
 * requests for that platform.
 *
 * Keyed on the query fields that change what a crawler fetches (text + filters), so two
 * searches that would hit the same URLs share an entry. Sold-only searches are not cached:
 * they feed price history and must stay live.
 *
 * What is stored is the market's whole answer, before the search has judged any of it. Storing the
 * survivors instead made everything the search removed disappear on the second look — the reasons
 * are worked out again from this each time it is served, so the list of what was removed is the
 * same whether the crawl just ran or is fifteen minutes old, and dropping a blocked word brings
 * its listings straight back instead of waiting for the entry to expire.
 */
object QueryResultCache {
    private const val TTL_MS = 15 * 60 * 1000L

    private data class Entry(val listings: List<Listing>, val storedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    // Past this many answers the oldest go, so the cache stays a size it can hold.
    private const val MAX_ENTRIES = 500

    /**
     * Everything about a query that changes what a crawler fetches. Leaving a field out serves
     * one search's answer to another that would have fetched differently: the key used to name a
     * few car criteria by hand, while crawlers put minimum mileage, maximum power, body type,
     * seats, doors, drive and seller type into their URLs too. So the car filters key the entry
     * whole. The results screen narrows what came back without changing the query, so that
     * costs no hits; what stays out is only what is judged after the cache (the words blocked,
     * the order, which markets and countries are shown, how a thing is sold).
     */
    fun key(platformId: PlatformId, query: SearchQuery): String = listOf(
        platformId.name,
        query.positiveText.lowercase().trim(),
        query.category,
        query.minPrice?.amount, query.maxPrice?.amount,
        query.condition?.sorted(),
        query.freeOnly,
        query.location?.lowercase()?.trim(), query.radiusKm,
        query.maxPages, query.startPage,
        query.carFilters,
        // The follow-up searches this decides are part of the answer that is stored.
        query.reach.otherWords, query.reach.extraTerms.map { it.lowercase().trim() }.sorted(),
    ).joinToString("|") { it?.toString() ?: "" }

    /** The market's whole answer if a fresh entry exists, else null. Sold-only queries never hit. */
    fun get(platformId: PlatformId, query: SearchQuery): List<Listing>? {
        if (query.soldOnly) return null
        val entry = entries[key(platformId, query)] ?: return null
        if (Clock.System.now().toEpochMilliseconds() - entry.storedAtMs > TTL_MS) {
            entries.remove(key(platformId, query))
            return null
        }
        return entry.listings
    }

    fun put(platformId: PlatformId, query: SearchQuery, listings: List<Listing>) {
        if (query.soldOnly) return
        // Never cache an empty result: a 0 is almost always transient (a block, a timeout, or the
        // anti-flag request cutoff), and caching it would hide real listings for the whole TTL.
        // The next request re-crawls instead.
        if (listings.isEmpty()) return
        val now = Clock.System.now().toEpochMilliseconds()
        entries[key(platformId, query)] = Entry(listings, now)
        if (entries.size > MAX_ENTRIES) {
            entries.entries.removeIf { now - it.value.storedAtMs > TTL_MS }
            val overflow = entries.size - MAX_ENTRIES
            if (overflow > 0) {
                entries.entries.sortedBy { it.value.storedAtMs }.take(overflow).forEach { entries.remove(it.key) }
            }
        }
    }
}
