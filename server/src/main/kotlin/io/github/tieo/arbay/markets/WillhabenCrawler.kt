package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import kotlinx.coroutines.CancellationException
import io.github.tieo.arbay.relevance.CarQueryResolver

/**
 * willhaben.at, read by Neuland. A search the words name a car in goes to its used-car section,
 * which takes the site's own make and model ids (Neuland reads them off its filter navigator) and
 * filters for first registration, mileage, price and power; everything else goes to its
 * marketplace by words. An ad's page gives the seller's text, the address and whether it is up.
 */
class WillhabenCrawler : Crawler, StartsAtAnyPage, FiltersAtTheSource, KnowsListingAge, KnowsLocation, KnowsAdState {
    override val nativeCriteria = setOf(FiltersAtTheSource.Criterion.YEAR, FiltersAtTheSource.Criterion.MILEAGE, FiltersAtTheSource.Criterion.PRICE, FiltersAtTheSource.Criterion.POWER)

    override val platformId = PlatformId.WILLHABEN

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        if (car != null) {
            val f = query.toCarFilters()
            val vehicle = NeulandOffers.VehicleQuery(
                make = car.makeSlug,
                model = car.modelSlug,
                registeredFrom = f?.firstRegFromYear,
                registeredTo = f?.firstRegToYear,
                mileageMinKm = f?.minMileageKm,
                mileageMaxKm = f?.maxMileageKm,
                powerMinKw = f?.minPowerKw,
            )
            // Every car sits under the site's /gebrauchtwagen/ path whatever its condition, so a
            // card that states none is of no known condition: the vehicle's own carries it.
            return NeulandOffers.searchVehicles(
                "willhaben.at", query.positiveText, platformId, "willhaben", vehicle, query.startPage,
                query.minPrice, query.maxPrice,
            )
        }
        // One page, unless the search asked for more offers than usual.
        val page: suspend (Int) -> List<Listing> = {
            NeulandOffers.search("willhaben.at", query.positiveText, platformId, "willhaben", it, query.minPrice, query.maxPrice, otherwise = Condition.USED)
        }
        return if (query.maxResults == null) page(query.startPage) else paginate(query) { page(it) }
    }

    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        val page = NeulandOffers.detail(listing.url, "willhaben")
        ListingDetail(vehicle = page.vehicle, description = page.description, location = page.location)
            .takeIf { it.description != null || it.location != null || it.vehicle != null }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        null
    }

    override suspend fun adState(url: String): AdState? = try {
        when (NeulandOffers.detail(url, "willhaben").available) {
            true -> AdState.LIVE
            false -> AdState.DELETED
            null -> null
        }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        null
    }
}
