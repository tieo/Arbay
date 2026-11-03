package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import kotlinx.coroutines.CancellationException

/**
 * The Adevinta market shared by marktplaats.nl (NL) and 2dehands.be (BE), read by Neuland through
 * the search JSON its pages are drawn from: a car search in its cars section (site-wide, "volkswagen
 * crafter" is thousands of parts), asked the gearbox, a fuel and, where the search excludes
 * unstated specs, the drive; everything else across the market with its price band.
 */
class MarktplaatsCrawler(
    override val platformId: PlatformId = PlatformId.MARKTPLAATS,
    private val site: String = "marktplaats.nl",
) : Crawler, StartsAtAnyPage, FetchesEveryPage, KnowsLocation, HasDetailSpecs {

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolve(query.positiveText)
        return paginate(query) { page ->
            if (car != null) {
                val c = query.carCriteria
                val vehicle = NeulandOffers.VehicleQuery(
                    make = car.makeSlug,
                    model = car.modelSlug,
                    gearbox = when (c.transmission) {
                        Transmission.AUTOMATIC -> "automatic"
                        Transmission.MANUAL -> "manual"
                        null -> null
                    },
                    fuels = c.fuels.map(NeulandOffers.VehicleQuery::fuel),
                    drivetrain = c.drivetrain?.takeIf { c.strictUnknown }?.let {
                        when (it) {
                            Drivetrain.AWD -> "all"
                            Drivetrain.FWD -> "front"
                            Drivetrain.RWD -> "rear"
                        }
                    },
                )
                NeulandOffers.searchVehicles(site, query.positiveText, platformId, platformId.displayName, vehicle, page, query.minPrice, query.maxPrice)
            } else {
                // Listed under the number the result pages' links carry ("m2434800158" is 2434800158,
                // a dealer's "a1524104910" is 1524104910), as offers found before were.
                NeulandOffers.search(site, query.positiveText, platformId, platformId.displayName, page, query.minPrice, query.maxPrice, otherwise = null)
                    .map { l -> l.externalId.replace(ITEM_LETTER, "").let { id -> l.copy(id = "${platformId.name}:$id", externalId = id) } }
            }
        }
    }

    /** The ad's page: the seller's whole text, who sells it and every photo, and for a vehicle what
     *  the card leaves out (power, drive, doors, seats, wheelbase, emission class), so a filter on
     *  those verifies instead of soft-passing. */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        val page = NeulandOffers.detail(listing.url, platformId.displayName)
        ListingDetail(vehicle = page.vehicle, description = page.description, seller = page.seller, photos = page.photos)
            .takeUnless { it == ListingDetail() }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        null
    }

    private companion object {
        /** The letter before an item's number: m for a private seller, a for a dealer. */
        val ITEM_LETTER = Regex("""^[am](?=\d)""")
    }
}
