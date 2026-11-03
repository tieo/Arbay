package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/**
 * bilbasen.dk, the Danish car market, read by Neuland: its cars by the make and model the words
 * name (or the words), asked its own filters for gearbox, first registration, mileage, price (in
 * kroner) and power (in horsepower).
 */
class BilbasenCrawler : Crawler, StartsAtAnyPage {
    override val platformId = PlatformId.BILBASEN

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText)
        val criteria = query.carCriteria
        val vehicle = NeulandOffers.VehicleQuery(
            make = car?.makeSlug?.replace("-", " "),
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
            NeulandOffers.searchVehicles("bilbasen.dk", query.positiveText, platformId, "Bilbasen", vehicle, page, query.minPrice, query.maxPrice)
        }
    }
}
