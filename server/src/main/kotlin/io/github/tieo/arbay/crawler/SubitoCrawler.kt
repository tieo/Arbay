package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/**
 * subito.it, Italy's largest classifieds market, read by Neuland: a search the words name a car in
 * goes to its car section with the price band, covering the private sellers AutoScout24's Italian
 * inventory largely misses; anything else to its used goods, which take no band.
 */
class SubitoCrawler : Crawler, StartsAtAnyPage, FetchesEveryPage, KnowsLocation {
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
}
