package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import kotlinx.coroutines.CancellationException

/**
 * truckscout24.de, the van and truck market, its vans up to 7.5 t, read by Neuland: by the make and
 * model the words name, cheapest first, inside the price band.
 *
 * A site's own filter removes every ad that states nothing for the field, the opposite of this
 * app's rule that an unstated spec keeps the listing and marks it unchecked. Of 521 Crafters here,
 * 519 state a mileage and 514 a fuel, but only 358 a gearbox, so mileage, power, fuel and gearbox
 * are asked at the source only when the search excludes unstated specs anyway; the rest are
 * filtered here. The site's build-year filter is broken, so Neuland never asks it.
 */
class TruckScout24Crawler(
    /** One page of the site's answer to a search; Neuland's reader of it. */
    private val page: suspend (SearchQuery, NeulandOffers.VehicleQuery, Int) -> List<Listing> = { query, vehicle, page ->
        NeulandOffers.searchVehicles("truckscout24.de", query.positiveText, PlatformId.TRUCKSCOUT24, "TruckScout24", vehicle, page, query.minPrice, query.maxPrice)
    },
) : Crawler, FiltersAtTheSource, KnowsLocation, FetchesEveryPage, SortsByPrice {
    override val nativeCriteria = setOf(FiltersAtTheSource.Criterion.PRICE)

    override val platformId = PlatformId.TRUCKSCOUT24

    override suspend fun search(query: SearchQuery): List<Listing> {
        val vehicle = vehicleQuery(query)
        // Twenty-five vans to a page; a page past the last answers 404, so a page short of full is
        // the last one asked for.
        var lastWasFull = true
        return paginate(query.wholePriceBand()) { number ->
            if (!lastWasFull) return@paginate emptyList()
            page(query, vehicle, number).also { lastWasFull = it.size >= PAGE_SIZE }
        }
    }

    /** What the site is asked beside the price band. */
    internal fun vehicleQuery(query: SearchQuery): NeulandOffers.VehicleQuery {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText)
        val criteria = query.carCriteria
        val strict = criteria.strictUnknown
        return NeulandOffers.VehicleQuery(
            make = car?.makeSlug,
            model = car?.modelSlug,
            gearbox = when (criteria.transmission.takeIf { strict }) {
                Transmission.AUTOMATIC -> "automatic"
                Transmission.MANUAL -> "manual"
                null -> null
            },
            mileageMinKm = criteria.minMileageKm.takeIf { strict },
            mileageMaxKm = criteria.maxMileageKm.takeIf { strict },
            powerMinKw = criteria.minPowerKw.takeIf { strict },
            powerMaxKw = criteria.maxPowerKw.takeIf { strict },
            fuels = if (strict) criteria.fuels.map(NeulandOffers.VehicleQuery::fuel) else emptyList(),
        )
    }

    /**
     * An ad's page: the dealer's text and the vehicle the site's rows state. From the prose only
     * the wheelbase is read, which no row carries. The load space measures are kept as a line of
     * the description, since they tell an L2 from an L3 where a title only claims one.
     */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        val page = NeulandOffers.detail(listing.url, "TruckScout24")
        val wheelbase = page.description?.let(VehicleTextParser::parseWheelbaseMm)
        val vehicle = (page.vehicle ?: VehicleInfo()).let { if (wheelbase != null) it.copy(wheelbaseMm = wheelbase) else it }
            .takeIf { it.verified.isNotEmpty() || wheelbase != null }
        val loadSpace = LOAD_SPACE.mapNotNull { label -> page.specs.firstOrNull { it.first == label }?.let { "$label: ${it.second}" } }
            .joinToString(", ").takeIf { it.isNotBlank() }
        val description = listOfNotNull(page.description, loadSpace).joinToString("\n\n").takeIf { it.isNotBlank() }
        ListingDetail(vehicle = vehicle, description = description).takeIf { it.vehicle != null || it.description != null }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        null
    }

    private companion object {
        const val PAGE_SIZE = 25
        val LOAD_SPACE = listOf("Laderaumlänge", "Laderaumbreite", "Laderaumhöhe", "Gesamtgewicht")
    }
}
