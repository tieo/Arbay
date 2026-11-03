package io.github.tieo.arbay.markets

import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.model.*

class VintedDeCrawler : Crawler, StartsAtAnyPage, KnowsAdState {

    override val platformId = PlatformId.VINTED_DE

    /** The seller's text off the item page, which the catalog card does not carry. */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        NeulandOffers.detail(listing.url.substringBefore("?"), "Vinted").description?.let { ListingDetail(description = it) }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        null
    }

    /** Whether the item is up, sold, or reserved or hidden for now, off its page. */
    override suspend fun adState(url: String): AdState? = try {
        val page = NeulandOffers.detail(url.substringBefore("?"), "Vinted")
        when {
            page.paused -> AdState.PAUSED
            page.available == true -> AdState.LIVE
            page.available == false -> AdState.DELETED
            else -> null
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        null
    }

    /** One page of the catalog, unless the search asked for more offers than usual: each page
     *  is a fetch Vinted counts towards its challenge, so the rest are read only when wanted. */
    override suspend fun search(query: SearchQuery): List<Listing> =
        if (query.maxResults == null) searchPage(query, query.startPage) else paginate(query) { searchPage(query, it) }

    /** One page of Vinted's catalog, read by Neuland, inside Vinted's own price band where the
     *  search has one: a search for the cheapest of a thing sold by the hundred reads only the pages
     *  inside the band. A card's price is what the buyer pays with Vinted's fee, the seller's own
     *  beside it. */
    private suspend fun searchPage(query: SearchQuery, page: Int): List<Listing> =
        NeulandOffers.search("vinted.de", query.positiveText, platformId, "Vinted", page, query.minPrice, query.maxPrice, otherwise = null)

}
