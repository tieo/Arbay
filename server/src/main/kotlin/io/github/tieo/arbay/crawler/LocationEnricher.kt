package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.Location
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

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

    private val log = LoggerFactory.getLogger(LocationEnricher::class.java)

    /** How many listings of one market a single search may fetch a location for. */
    private const val MAX_FETCHES_PER_PLATFORM = 12

    private val gate = Semaphore(3)
    private val cache = ConcurrentHashMap<String, Location>()
    // Listings whose page was read and says nowhere where the thing is. A page that could not be
    // read is not in here: that is the market failing this once, and the next search asks again.
    private val statesNoPlace = ConcurrentHashMap.newKeySet<String>()

    /** A location already fetched for this listing, if any. */
    fun cached(listingId: String): Location? = cache[listingId]

    /** Fetch and remember one listing's location. Null when the market does not publish one, or
     *  when the page could not be read. */
    suspend fun fetch(listing: Listing, crawler: Crawler): Location? {
        cache[listing.id]?.let { return it }
        if (listing.id in statesNoPlace) return null
        // The whole page answers at once and is kept, so a later spec lookup for this listing
        // does not load the same defended page a second time.
        val detail = DetailCache.get(listing.id) ?: crawler.fetchDetail(listing)?.also {
            DetailCache.put(listing.id, it)
        } ?: return null
        val found = detail.location
        if (found == null) {
            statesNoPlace.add(listing.id)
            return null
        }
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
        // Fetched side by side, as many at once as the gate lets through; one page that fails
        // costs that listing its place and nothing else.
        val fetched = coroutineScope {
            toFetch.map { id ->
                val listing = missing.first { it.id == id }
                async {
                    id to try {
                        gate.withPermit { fetch(listing, crawler) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.debug("No location for {}: {}", id, e.message)
                        null
                    }
                }
            }.awaitAll().toMap()
        }
        return listings.map { listing ->
            if (listing.location != null) return@map listing
            val found = fetched[listing.id] ?: cache[listing.id] ?: return@map listing
            listing.copy(location = found)
        }
    }

    private fun priceEurCents(listing: Listing): Long =
        if (listing.price.currency == io.github.tieo.arbay.model.Currency.EUR) listing.price.amount
        else ExchangeRates.convert(listing.price.amount, listing.price.currency.name, "EUR")
}
