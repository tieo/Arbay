package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.relevance.CarQueryResolver

/**
 * OLX's car markets, otomoto.pl and autovit.ro, read by Neuland: by the make and model the words
 * name, around the searched place where it lies in the site's country, asked the site's own
 * filters for first registration, mileage, price, power, fuel and gearbox. Four-wheel drive is an
 * equipment flag there that sellers tick or do not, so it is asked only by a search that excludes
 * unstated specs anyway.
 */
class OtomotoCrawler(
    override val platformId: PlatformId = PlatformId.OTOMOTO,
    private val site: String = "otomoto.pl",
    private val countryCode: String = "PL",
    private val siteLabel: String = "OTOMoto",
) : Crawler, StartsAtAnyPage, FetchesEveryPage, FiltersAtTheSource, KnowsLocation {
    override val nativeCriteria = setOf(FiltersAtTheSource.Criterion.YEAR, FiltersAtTheSource.Criterion.MILEAGE, FiltersAtTheSource.Criterion.PRICE, FiltersAtTheSource.Criterion.POWER, FiltersAtTheSource.Criterion.GEARBOX)

    override suspend fun search(query: SearchQuery): List<Listing> {
        // The make and model are the whole filter here: without them the site answers every query
        // with the same catalogue, so nothing is asked.
        val car = CarQueryResolver.resolveForCarSite(query.positiveText) ?: return emptyList()
        val criteria = query.carCriteria
        val vehicle = NeulandOffers.VehicleQuery(
            make = car.makeSlug,
            model = car.modelSlug,
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
            drivetrain = "all".takeIf { criteria.strictUnknown && criteria.drivetrain == Drivetrain.AWD },
        )
        // A place this country does not know resolves to nothing, so a German town never reaches
        // a Polish search.
        val area = query.area(countryCode)
        return paginate(query) { page ->
            NeulandOffers.searchVehicles(site, query.positiveText, platformId, siteLabel, vehicle, page, query.minPrice, query.maxPrice, area)
        }
    }
}
