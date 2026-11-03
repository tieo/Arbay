package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/**
 * Idealo, read by Neuland: its products, each priced by the shop cheapest to have delivered and
 * carrying every shop, and its single offers of one shop with that shop's delivery charge. The shop
 * with a product's lowest item price is often not the cheapest delivered: alza.de's 11,49 € for the
 * Cudy GS108U costs 16,48 € with delivery, beside Amazon's 17,90 €.
 */
class IdealoCrawler : Crawler {
    override val platformId = PlatformId.IDEALO

    override suspend fun search(query: SearchQuery): List<Listing> {
        val listings = NeulandOffers.search("idealo.de", query.positiveText, platformId, "Idealo").map { it.copy(title = it.title.tidyTitle()) }
        // Idealo answers a word for a kind of thing ("laptop") from its own category, whose products
        // carry the category's name rather than the search's words.
        if (listings.isNotEmpty() && listings.none { it.title.contains(query.positiveText, ignoreCase = true) }) {
            emitAnsweredFromCategory()
        }
        return listings
    }

    /** Every shop of a product, cheapest delivered first, read anew; a single offer has none. */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? {
        val sellers = NeulandOffers.sellers(listing.url)
        if (sellers.isEmpty()) return null
        return ListingDetail(sellers = sellers, shipping = sellers.firstOrNull { it.shipping != null }?.shipping?.let(NeulandOffers::delivery))
    }
}
