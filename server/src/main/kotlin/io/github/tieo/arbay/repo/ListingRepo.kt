package io.github.tieo.arbay.repo

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import java.util.concurrent.ConcurrentHashMap

class ListingRepo {
    private val listings = ConcurrentHashMap<String, Listing>()

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
        return listing
    }

    fun upsertBatch(batch: List<Listing>): Int {
        batch.forEach { listings[it.id] = it }
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
            .sortedBy { it.soldDate ?: it.scrapedAt }
    }
}
