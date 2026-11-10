package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.neuland.NeulandOffers
import kotlinx.coroutines.CancellationException
import io.github.tieo.arbay.relevance.CarQueryResolver
import io.github.tieo.arbay.relevance.VehicleTextParser

/**
 * AutoScout24, read by Neuland: by the make and model the words name, in [countries] (ISO codes),
 * cheapest first, around the searched place where there is one (in that place's country alone).
 *
 * The site's own filter removes every ad that states nothing for the field, and this app keeps
 * those, marked unchecked. So a condition goes to the site only where its ads nearly all state it,
 * measured against 1625 Crafters (asking a range that excludes nothing): registration, mileage,
 * price, fuel and the kind of seller always; the gearbox always as well, since every ad states one
 * (72 of 72, 2026-10-21) and leaving it out filled the 20 pages the site serves with the other
 * gearbox; doors (1606), seats (1614), power and four-wheel drive (an equipment flag) only for a
 * search that excludes unstated specs anyway; emission class (1267) and body (446) never.
 */
class AutoScout24Crawler(
    private val countries: List<String> = listOf("DE"),
    override val platformId: PlatformId = PlatformId.AUTOSCOUT24,
) : Crawler, StartsAtAnyPage, FetchesEveryPage, FiltersAtTheSource, KnowsLocation, SortsByPrice, KnowsAdState {
    override val nativeCriteria = setOf(
        FiltersAtTheSource.Criterion.YEAR, FiltersAtTheSource.Criterion.MILEAGE,
        FiltersAtTheSource.Criterion.PRICE, FiltersAtTheSource.Criterion.FUEL,
    )

    override suspend fun search(query: SearchQuery): List<Listing> {
        // A query that names no make the site knows is not sent: the site ignores the words and
        // answers its default feed, which the relevance filter throws away whole.
        val vehicle = vehicleQuery(query) ?: return emptyList()
        val area = query.area(countries.first())
        return paginate(query.wholePriceBand()) { page ->
            NeulandOffers.searchVehicles(
                "autoscout24.de", query.positiveText, platformId, "AutoScout24", vehicle, page,
                query.minPrice, query.maxPrice, area, countries = countries,
            )
        }
    }

    /** What the site is asked beside the price band and the place; none where the words name no car. */
    internal fun vehicleQuery(query: SearchQuery): NeulandOffers.VehicleQuery? {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText) ?: return null
        val c = query.carCriteria.atTheSource()
        return NeulandOffers.VehicleQuery(
            make = car.makeSlug,
            model = car.modelSlug,
            gearbox = when (query.carCriteria.transmission) {
                Transmission.AUTOMATIC -> "automatic"
                Transmission.MANUAL -> "manual"
                null -> null
            },
            registeredFrom = c.firstRegFromYear,
            registeredTo = c.firstRegToYear,
            mileageMinKm = c.minMileageKm,
            mileageMaxKm = c.maxMileageKm,
            powerMinKw = c.minPowerKw,
            powerMaxKw = c.maxPowerKw,
            fuels = c.fuels.map(NeulandOffers.VehicleQuery::fuel),
            drivetrain = "all".takeIf { c.strictUnknown && c.drivetrain == Drivetrain.AWD },
            doorsMin = c.minDoors.takeIf { c.strictUnknown },
            seatsMin = c.minSeats.takeIf { c.strictUnknown },
            dealer = c.sellerType?.let { it != SellerType.PRIVATE },
        )
    }

    /**
     * What the card never carries, off the ad's own page: the seller's own text, the equipment
     * they ticked (what their prose often leaves out, air conditioning at all) and when the ad
     * went up. The site's own wheelbase field was empty on every Crafter measured; where one is
     * stated it is a line of the dealer's text ("Radstand 3640 mm"), so only the wheelbase is read
     * from the prose: anything else read there could overwrite what the card states
     * ("130.000 km Garantie").
     */
    /**
     * Whether the ad is up, off one fresh read of its page. The page states an ad that is up
     * ("status": "active"); one taken down is not told apart from a page that failed to read, so
     * that stays unknown rather than read as gone.
     */
    override suspend fun adState(url: String): AdState? = try {
        AdState.LIVE.takeIf { NeulandOffers.detail(url, "AutoScout24").available == true }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        null
    }

    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        val page = NeulandOffers.detail(listing.url, "AutoScout24")
        val wheelbase = page.description?.let(VehicleTextParser::parseWheelbaseMm)
        val equipment = page.specs.firstOrNull { it.first == "Ausstattung" }?.second
        ListingDetail(
            vehicle = wheelbase?.let { VehicleInfo(wheelbaseMm = it, verified = setOf(VehicleField.WHEELBASE)) },
            description = listOfNotNull(page.description, equipment?.let { "Ausstattung: $it" })
                .joinToString("\n\n").takeIf { it.isNotBlank() },
            listedAt = page.listedAt,
        ).takeIf { it.vehicle != null || it.description != null }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        null
    }
}
