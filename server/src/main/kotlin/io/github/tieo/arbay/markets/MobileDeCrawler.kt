package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import io.github.tieo.arbay.relevance.CarQueryResolver
import io.github.tieo.arbay.relevance.VehicleTextParser

/**
 * mobile.de, read by Neuland in its browser past the site's Akamai defence: cars by the make and
 * model the words name (the site's own numbers for them, its free text reading only the sellers'
 * prose), cheapest first, around the searched place, each card stating its first registration,
 * mileage, power, fuel, gearbox, seats, emission class and seller.
 *
 * A site's filter removes every ad that states nothing for the field, which this app keeps and
 * marks unchecked, so the fields most of its ads leave empty are asked only by a search that
 * excludes unstated specs anyway. Measured over 2875 Crafters: a drive type on 2221 (77%), doors on
 * 258 (9%), seats on 2444 (85%), an emission class on 2158 (75%); fuel and body on effectively all.
 */
class MobileDeCrawler : Crawler, FiltersAtTheSource, KnowsLocation, HasDetailSpecs, ReadsAdsTogether, SortsByPrice, KnowsAdState, StartsAtAnyPage {
    override val nativeCriteria = setOf(
        FiltersAtTheSource.Criterion.YEAR, FiltersAtTheSource.Criterion.MILEAGE,
        FiltersAtTheSource.Criterion.PRICE, FiltersAtTheSource.Criterion.FUEL,
        FiltersAtTheSource.Criterion.BODY, FiltersAtTheSource.Criterion.CONDITION,
    )

    override val platformId = PlatformId.MOBILE_DE

    private val log = LoggerFactory.getLogger(MobileDeCrawler::class.java)

    override suspend fun search(query: SearchQuery): List<Listing> {
        val vehicle = vehicleQuery(query)
        val area = query.area("DE")
        return paginate(query.wholePriceBand()) { page ->
            NeulandOffers.searchVehicles("mobile.de", query.positiveText, platformId, "mobile.de", vehicle, page, query.minPrice, query.maxPrice, area)
        }
    }

    /**
     * What the site is asked beside the price band and the place. The gearbox goes with a search
     * somebody waits for; one nobody waits for (a saved search's watch) reads every gearbox, as
     * each card states its own.
     */
    internal fun vehicleQuery(query: SearchQuery): NeulandOffers.VehicleQuery {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText)
        val f = query.toCarFilters()?.atTheSource()
        val strict = f?.strictUnknown == true
        val wish = query.toCarFilters()?.transmission?.takeIf { !query.unattended || strict }
        return NeulandOffers.VehicleQuery(
            make = car?.makeSlug?.replace("-", " "),
            model = car?.modelSlug?.replace("-", " "),
            gearbox = when (wish) {
                Transmission.AUTOMATIC -> "automatic"
                Transmission.MANUAL -> "manual"
                null -> null
            },
            registeredFrom = f?.firstRegFromYear,
            registeredTo = f?.firstRegToYear,
            mileageMinKm = f?.minMileageKm,
            mileageMaxKm = f?.maxMileageKm,
            powerMinKw = f?.minPowerKw,
            powerMaxKw = f?.maxPowerKw,
            fuels = f?.fuels.orEmpty().map(NeulandOffers.VehicleQuery::fuel),
            conditions = NeulandOffers.VehicleQuery.conditions(f?.conditions.orEmpty()),
            bodies = f?.bodyTypes.orEmpty().map(NeulandOffers.VehicleQuery::body),
            dealer = f?.sellerType?.let { it != SellerType.PRIVATE },
            drivetrain = f?.drivetrain?.takeIf { strict }?.let {
                when (it) {
                    Drivetrain.AWD -> "all"
                    Drivetrain.FWD -> "front"
                    Drivetrain.RWD -> "rear"
                }
            },
            doorsMin = f?.minDoors?.takeIf { strict },
            seatsMin = f?.minSeats?.takeIf { strict },
            emissionMin = f?.minEmissionEuro?.takeIf { strict },
        )
    }

    /** Whether the ad is still up, off one fresh read of its page; null when the page could not be read. */
    override suspend fun adState(url: String): AdState? = try {
        when (NeulandOffers.detail(url, "mobile.de").available) {
            true -> AdState.LIVE
            false -> AdState.DELETED
            null -> null
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug("mobile.de ad state unreadable for {}: {}", url, e.message)
        null
    }

    override suspend fun fetchDetail(listing: Listing): ListingDetail? = fetchDetails(listOf(listing))[listing.id]

    /**
     * Each ad's own page. The card says "Volkswagen Crafter" and nothing about which one. The page
     * has the seller's headline, the trim line the dealer picked from the site's own catalogue ("35
     * lang Hochdach FWD Trendline"), the equipment list, which is where the drive is
     * ("Frontantrieb"), and the seller's text, which is where a wheelbase is written ("Radstand
     * 4490 mm"). The ads read before one fails stand; the rest stay as their cards say.
     */
    override suspend fun fetchDetails(listings: List<Listing>): Map<String, ListingDetail> {
        val found = HashMap<String, ListingDetail>()
        for (listing in listings) {
            val page = try {
                NeulandOffers.detail("https://suchen.mobile.de/fahrzeuge/details.html?id=${listing.externalId}", "mobile.de")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("mobile.de ads: {} of {} read, then {}", found.size, listings.size, e.message)
                break
            }
            detailOf(page)?.let { found[listing.id] = it }
        }
        log.info("mobile.de ads: {} of {} read", found.size, listings.size)
        return found
    }

    /** An ad page as Arbay keeps it: the stated drive, seats and emission class, the wheelbase from
     *  the seller's text, and the headline, trim line, air conditioning, equipment and text, in the
     *  order the page shows them, which is also the order the size is read from. */
    internal fun detailOf(page: NeulandOffers.Detail): ListingDetail? {
        val stated = page.vehicle ?: VehicleInfo()
        val wheelbase = page.description?.let(VehicleTextParser::parseWheelbaseMm)
        val vehicle = VehicleInfo(
            drivetrain = stated.drivetrain,
            wheelbaseMm = wheelbase,
            seats = stated.seats,
            emissionClassEuro = stated.emissionClassEuro,
            verified = buildSet {
                if (stated.drivetrain != null) add(VehicleField.DRIVETRAIN)
                if (wheelbase != null) add(VehicleField.WHEELBASE)
                if (stated.seats != null) add(VehicleField.SEATS)
                if (stated.emissionClassEuro != null) add(VehicleField.EMISSION)
            },
        )
        val spec = { label: String -> page.specs.firstOrNull { it.first == label }?.second }
        val description = listOfNotNull(
            spec("Titel"),
            spec("Ausstattungslinie")?.let { "Ausstattungslinie: $it" },
            spec("Klimatisierung")?.let { "Klimatisierung: $it" },
            spec("Ausstattung")?.let { "Ausstattung: $it" },
            page.description,
        ).joinToString("\n\n").takeIf { it.isNotBlank() }
        return ListingDetail(
            vehicle = vehicle.takeIf { it.verified.isNotEmpty() },
            description = description,
            listedAt = page.listedAt,
            sellerReviews = page.sellerReviews,
        ).takeIf { it.vehicle != null || it.description != null }
    }
}
