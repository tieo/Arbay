package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.relevance.CarQueryResolver

/**
 * sauto.cz, the Czech vehicle market, read by Neuland: vehicles by the make and model the words
 * name, in whichever of the site's categories holds the model, asked the site's own filters for
 * first registration, mileage, fuel, power, price (in koruna) and gearbox.
 */
class SautoCrawler : Crawler, StartsAtAnyPage {
    override val platformId = PlatformId.SAUTO

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
            mileageMinKm = criteria.minMileageKm,
            mileageMaxKm = criteria.maxMileageKm,
            powerMinKw = criteria.minPowerKw,
            fuels = criteria.fuels.map(NeulandOffers.VehicleQuery::fuel),
        )
        return paginate(query) { page ->
            NeulandOffers.searchVehicles("sauto.cz", query.positiveText, platformId, "Sauto", vehicle, page, query.minPrice, query.maxPrice)
        }
    }
}
