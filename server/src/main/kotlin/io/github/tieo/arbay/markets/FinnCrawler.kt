package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.relevance.CarQueryResolver

/**
 * finn.no, Norway's market's used cars, read by Neuland through Schibsted's mobility search: by the make and model the words name
 * (or the words), asked the site's own filters for first registration, mileage, power, price and
 * gearbox.
 */
class FinnCrawler : Crawler, StartsAtAnyPage {
    override val platformId = PlatformId.FINN

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText)
        val criteria = query.carCriteria
        val vehicle = NeulandOffers.VehicleQuery(
            make = car?.makeSlug,
            model = car?.modelSlug,
            gearbox = when (criteria.transmission) {
                Transmission.AUTOMATIC -> "automatic"
                Transmission.MANUAL -> "manual"
                null -> null
            },
            registeredFrom = criteria.firstRegFromYear,
            registeredTo = criteria.firstRegToYear,
            mileageMaxKm = criteria.maxMileageKm,
            powerMinKw = criteria.minPowerKw,
        )
        return paginate(query) { page ->
            NeulandOffers.searchVehicles("finn.no", query.positiveText, platformId, "FINN", vehicle, page, query.minPrice, query.maxPrice)
        }
    }
}
