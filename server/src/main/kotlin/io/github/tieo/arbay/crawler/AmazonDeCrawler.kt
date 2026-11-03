package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/**
 * Amazon Germany's search, read by Neuland (in its browser where Amazon refuses its plain client),
 * with the price band: three pages at most, as Amazon blocks after about that many.
 */
class AmazonDeCrawler : Crawler, StartsAtAnyPage {
    override val platformId = PlatformId.AMAZON_DE

    override suspend fun search(query: SearchQuery): List<Listing> {
        val found = mutableListOf<Listing>()
        val seen = mutableSetOf<String>()
        for (page in query.startPage until query.startPage + query.pageLimit(cap = 3)) {
            val results = try {
                NeulandOffers.search("amazon.de", query.positiveText, platformId, "Amazon", page, query.minPrice, query.maxPrice)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (page == query.startPage) throw e
                break
            }
            val fresh = results.filter { seen.add(it.externalId) }
            found += fresh
            if (fresh.size < 10 || found.size >= query.resultCap()) break
        }
        return found
    }
}
