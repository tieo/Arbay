package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.relevance.CarQueryResolver

/**
 * A carmaker's own stock search, read by Neuland: the new, pre-registered, demonstrator and used
 * cars its German dealers (or the maker itself) have, which the classified sites list only in part.
 * Asked by the make and model the words name, with the search's fuel, condition and price band; a
 * make the maker does not sell is not asked at all.
 */
class MakerStockCrawler(
    override val platformId: PlatformId,
    private val site: String,
    /** The makes, by the car sites' slugs, whose stock this search holds. */
    private val makes: Set<String>,
) : Crawler, FiltersAtTheSource, StartsAtAnyPage {
    override val nativeCriteria = setOf(
        FiltersAtTheSource.Criterion.PRICE, FiltersAtTheSource.Criterion.FUEL, FiltersAtTheSource.Criterion.CONDITION,
    )

    override suspend fun search(query: SearchQuery): List<Listing> {
        val vehicle = vehicleQuery(query) ?: return emptyList()
        return paginate(query.wholePriceBand()) { page ->
            NeulandOffers.searchVehicles(site, query.positiveText, platformId, platformId.displayName, vehicle, page, query.minPrice, query.maxPrice)
        }
    }

    /** What the maker's search is asked; none where the words name no make it sells. */
    internal fun vehicleQuery(query: SearchQuery): NeulandOffers.VehicleQuery? {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText)?.takeIf { it.makeSlug in makes } ?: return null
        val c = query.carCriteria.atTheSource()
        return NeulandOffers.VehicleQuery(
            make = car.makeSlug.replace("-", " "),
            model = car.modelSlug?.replace("-", " "),
            fuels = c.fuels.map(NeulandOffers.VehicleQuery::fuel),
            conditions = NeulandOffers.VehicleQuery.conditions(c.conditions),
            registeredFrom = c.firstRegFromYear,
            registeredTo = c.firstRegToYear,
            mileageMaxKm = c.maxMileageKm,
        )
    }
}
