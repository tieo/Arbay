package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import kotlinx.coroutines.CancellationException
import io.github.tieo.arbay.relevance.CarQueryResolver

/**
 * subito.it, Italy's largest classifieds market, read by Neuland: a search the words name a car in
 * goes to its car section with the price band, covering the private sellers AutoScout24's Italian
 * inventory largely misses; anything else to its used goods, which take no band.
 */
class SubitoCrawler : Crawler, StartsAtAnyPage, FetchesEveryPage, KnowsLocation, KnowsAdState {
    override val platformId = PlatformId.SUBITO

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        return paginate(query) { page ->
            if (car != null) {
                NeulandOffers.searchVehicles(
                    "subito.it", query.positiveText, platformId, "Subito",
                    NeulandOffers.VehicleQuery(make = car.makeSlug, model = car.modelSlug), page, query.minPrice, query.maxPrice,
                )
            } else {
                NeulandOffers.search("subito.it", query.positiveText, platformId, "Subito", page, query.minPrice, query.maxPrice, otherwise = null)
            }
        }
    }

    /**
     * The item page: the seller's text, the condition in Subito's words, the town, the seller and
     * the photos, for a vehicle what the card leaves out. Shipping is left as the card has it:
     * Subito's own shipping reaches addresses in Italy only, so the cost its page names is no cost
     * of sending it to Germany.
     */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        val page = NeulandOffers.detail(listing.url, "Subito")
        ListingDetail(
            vehicle = page.vehicle,
            description = page.description,
            location = page.location,
            seller = page.seller,
            sellerReviews = page.sellerReviews,
            photos = page.photos,
            conditionNote = page.conditionNote,
        ).takeIf { it.description != null || it.photos.isNotEmpty() || it.vehicle != null }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        null
    }

    /** Whether the ad is up, off its page; null where the page does not say. */
    override suspend fun adState(url: String): AdState? = try {
        val page = NeulandOffers.detail(url, "Subito")
        when {
            page.paused -> AdState.PAUSED
            page.available == true -> AdState.LIVE
            page.available == false -> AdState.DELETED
            else -> null
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        null
    }
}
