package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.relevance.CarQueryResolver

/**
 * autoplius.lt, Lithuania's largest vehicle market, read by Neuland in its browser: by the make and
 * model path, the site's only search that answers what it is asked (its keyword search answered
 * BMWs for "volkswagen crafter"), so words naming no car are not searched there.
 */
class AutopliusCrawler : Crawler, StartsAtAnyPage {
    override val platformId = PlatformId.AUTOPLIUS

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText) ?: return emptyList()
        val vehicle = NeulandOffers.VehicleQuery(make = car.makeSlug, model = car.modelSlug)
        return paginate(query) { page ->
            NeulandOffers.searchVehicles("autoplius.lt", query.positiveText, platformId, "Autoplius", vehicle, page, query.minPrice, query.maxPrice)
        }
    }
}
