package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/**
 * OLX Portugal's cars, read by Neuland through the site's JSON: the model the words name by the
 * site's own model filter (its words match loosely: "volkswagen crafter" answered Golfs), with the
 * conditions its ads nearly all state; the rest are filtered here.
 */
class OlxCrawler : Crawler, StartsAtAnyPage {
    override val platformId = PlatformId.OLX_PT

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        val c = query.toCarFilters()?.atTheSource()
        val vehicle = NeulandOffers.VehicleQuery(
            make = car?.makeSlug,
            model = car?.modelSlug,
            gearbox = when (c?.transmission) {
                Transmission.AUTOMATIC -> "automatic"
                Transmission.MANUAL -> "manual"
                null -> null
            },
            registeredFrom = c?.firstRegFromYear,
            registeredTo = c?.firstRegToYear,
            mileageMinKm = c?.minMileageKm,
            mileageMaxKm = c?.maxMileageKm,
            powerMinKw = c?.minPowerKw,
            fuels = c?.fuels.orEmpty().map(NeulandOffers.VehicleQuery::fuel),
        )
        return paginate(query) { page ->
            NeulandOffers.searchVehicles("olx.pt", query.positiveText, platformId, "OLX PT", vehicle, page, query.minPrice, query.maxPrice)
        }
    }
}
