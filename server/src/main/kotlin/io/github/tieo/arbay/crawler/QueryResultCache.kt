package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.carCriteria
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
 */
object QueryResultCache {
    private const val TTL_MS = 15 * 60 * 1000L

    private data class Entry(val listings: List<Listing>, val storedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun key(platformId: PlatformId, query: SearchQuery): String = listOf(
        platformId.name,
        query.positiveText.lowercase().trim(),
        query.minPrice?.amount, query.maxPrice?.amount,
        query.carCriteria.firstRegFromYear, query.carCriteria.firstRegToYear,
        query.carCriteria.maxMileageKm, query.carCriteria.minPowerKw, query.carCriteria.transmission,
        query.location?.lowercase()?.trim(), query.radiusKm,
        // Fuel is baked into some crawlers' fetch URL (Kleinanzeigen native filter), so it
        // changes what is fetched and must key the entry. Post-filter-only dims (body, colour,
        // van size, description, …) are enforced after the cache and stay out of the key.
        query.carFilters?.fuels?.map { it.name }?.sorted()?.joinToString(","),
    ).joinToString("|") { it?.toString() ?: "" }

    /** Cached listings if a fresh entry exists, else null. Sold-only queries never hit. */
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
        entries[key(platformId, query)] = Entry(listings, Clock.System.now().toEpochMilliseconds())
    }
}
