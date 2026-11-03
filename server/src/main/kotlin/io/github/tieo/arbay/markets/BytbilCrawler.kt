package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.relevance.CarQueryResolver

/**
 * bytbil.com, the Swedish vehicle market, its van section, read by Neuland: vans by the make the
 * words name and the model as free text (or the words), asked the site's own filters for first
 * registration, mileage, price (in kronor), power (in horsepower) and gearbox.
 */
class BytbilCrawler : Crawler, StartsAtAnyPage {
    override val platformId = PlatformId.BYTBIL

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
            NeulandOffers.searchVehicles("bytbil.com", query.positiveText, platformId, "Bytbil", vehicle, page, query.minPrice, query.maxPrice)
        }
    }
}
