package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/**
 * nettiauto.com, Finland's largest vehicle market, read by Neuland: its cars by the make and model
 * the words name (or the words), asked years, mileage and price; each card's facts come as the site
 * states them, so they may exclude on a filter.
 */
class NettiautoCrawler : Crawler, StartsAtAnyPage {
    override val platformId = PlatformId.NETTIAUTO

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText)
        val f = query.carCriteria
        val vehicle = NeulandOffers.VehicleQuery(
            make = car?.makeSlug,
            model = car?.modelSlug,
            registeredFrom = f.firstRegFromYear,
            registeredTo = f.firstRegToYear,
            mileageMaxKm = f.maxMileageKm,
        )
        return paginate(query) { page ->
            NeulandOffers.searchVehicles("nettiauto.com", query.positiveText, platformId, "Nettiauto", vehicle, page, query.minPrice, query.maxPrice)
        }
    }
}
