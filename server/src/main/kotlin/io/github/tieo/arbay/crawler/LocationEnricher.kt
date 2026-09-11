package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.Location
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Fills in where a listing is, for the markets that say it on the item page and not on the card.
 *
 * eBay is the one that does this: its search cards carry a price, a shipping line, a watcher count
 * and a seller rating, and nothing about where the thing stands — checked against a fetched page
 * with its radius filter switched on, which changes neither the cards nor which items come back.
 * The item page states it plainly ("Standort: Hamburg, Deutschland"), so a location costs one page
 * load per listing, on a market that answers a plain client with a challenge and needs the browser.
 *
 * That price is why this is budgeted and only spent where the answer changes what is shown: a
 * search centred on a place, where a listing with no location cannot be measured against it. Each
 * answer is kept for the life of the process, so re-running the same search costs nothing.
 */
object LocationEnricher {

    /** How many listings of one market a single search may fetch a location for. */
    private const val MAX_FETCHES_PER_PLATFORM = 12

    private val gate = Semaphore(3)
    private val cache = ConcurrentHashMap<String, Location>()
    private val known = ConcurrentHashMap.newKeySet<String>()

    /** A location already fetched for this listing, if any. */
    fun cached(listingId: String): Location? = cache[listingId]

    /** Fetch and remember one listing's location. Null when the market does not publish one, or
     *  when the page could not be read. */
    suspend fun fetch(listing: Listing, crawler: Crawler): Location? {
        cache[listing.id]?.let { return it }
        if (!known.add(listing.id)) return null
        // The whole page answers at once and is kept, so a later spec lookup for this listing
        // does not load the same defended page a second time.
        val detail = DetailCache.get(listing.id) ?: crawler.fetchDetail(listing)?.also {
            DetailCache.put(listing.id, it)
        }
        val found = detail?.location ?: return null
        cache[listing.id] = found
        return found
    }

    /**
     * Fill in the listings that have no location, cheapest first, up to the budget.
     *
     * Cheapest first for the same reason the spec enricher works that way: the cap decides which
     * listings get an answer, and the cheap end is the end a price comparison is about.
     */
    suspend fun enrich(listings: List<Listing>, crawler: Crawler): List<Listing> {
        val missing = listings.filter { it.location == null }
        if (missing.isEmpty()) return listings
        val toFetch = missing
            .filter { cache[it.id] == null }
            .sortedBy { priceEurCents(it) }
            .take(MAX_FETCHES_PER_PLATFORM)
            .mapTo(HashSet()) { it.id }
        return listings.map { listing ->
            if (listing.location != null) return@map listing
            cache[listing.id]?.let { return@map listing.copy(location = it) }
            if (listing.id !in toFetch) return@map listing
            val found = gate.withPermit { fetch(listing, crawler) } ?: return@map listing
            listing.copy(location = found)
        }
    }

    private fun priceEurCents(listing: Listing): Long =
        if (listing.price.currency == io.github.tieo.arbay.model.Currency.EUR) listing.price.amount
        else ExchangeRates.convert(listing.price.amount, listing.price.currency.name, "EUR")
}
