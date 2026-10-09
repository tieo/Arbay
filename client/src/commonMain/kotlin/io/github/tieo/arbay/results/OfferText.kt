package io.github.tieo.arbay.results

import io.github.tieo.arbay.ImportRules
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.format
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.Location
import io.github.tieo.arbay.model.NotificationSubfilter
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.VehicleInfo
import io.github.tieo.arbay.model.importVat
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.round
import kotlin.time.Clock

/**
 * One offer as it reads in full: what the search card carried, with what its own page adds. The
 * page's vehicle facts fill the gaps the card left, its description replaces a shorter one, and its
 * place stands in where the card had none.
 */
data class OfferReading(val vehicle: VehicleInfo?, val description: String?, val place: Location?)

fun readOffer(listing: Listing, fromItsPage: ListingDetail?): OfferReading {
    val vehicle = fromItsPage?.vehicle?.let { page ->
        listing.vehicle?.let { card -> card.copy(wheelbaseMm = card.wheelbaseMm ?: page.wheelbaseMm, verified = card.verified + page.verified) } ?: page
    } ?: listing.vehicle
    val description = fromItsPage?.description?.takeIf { it.length > (listing.description?.length ?: 0) } ?: listing.description
    return OfferReading(vehicle, description, listing.location ?: fromItsPage?.location)
}

/** The short facts of an offer, each one a few words: condition, place, distance, age, how it is
 *  sold and delivered, and by whom. */
fun offerFacts(listing: Listing, place: Location?): List<String> = buildList {
    listing.condition?.let { add(it.label) }
    place?.let { loc -> listOfNotNull(loc.zip, loc.city ?: loc.raw ?: loc.country).joinToString(" ").takeIf { it.isNotBlank() }?.let(::add) }
    listing.distanceKm?.let { add("${it.toInt()} km away") }
    listing.listingDate?.let { add("posted ${it.toLocalDateTime(TimeZone.currentSystemDefault()).date}") }
    if (listing.saleType == SaleType.AUCTION) add(listing.bidCount?.let { "$it bids" } ?: "auction")
    listing.shipping?.cost?.takeIf { it.amount > 0 }?.let { add("delivery ${it.format()}") }
    if (listing.shipping?.free == true) add("free delivery")
    if (listing.negotiable) add("negotiable")
    listing.seller?.takeIf { !it.name.isNullOrBlank() }?.let { s -> add("sold by ${s.name}" + (s.rating?.let { " · $it★" } ?: "")) }
}

/** How an offer's price sits against the middle one, when it is far enough off to be worth saying. */
fun PriceSummary.againstMiddle(listing: Listing): String? =
    belowMiddle(listing.comparablePrice)?.takeIf { abs(it) >= 0.05 }?.let { share ->
        val pct = round(abs(share) * 100).toInt()
        if (share > 0) "$pct% under the middle offer" else "$pct% over the middle offer"
    }

/** What the landed price adds to the market's own, for an offer from outside the VAT area. */
fun importVatNote(listing: Listing): String? = listing.importVat(ImportRules.current)?.let { vat ->
    "Includes ${ImportRules.current.importVatPercent}% import VAT of ${vat.format()}; ${listing.price.format()} on ${listing.platformId.displayName}."
}

/** A copy of an offer on another market, named for its market, with its own price where it differs. */
fun copyLabel(copy: Listing, of: Listing): String =
    "Also on ${copy.platformId.displayName}" + if (copy.comparablePrice != of.comparablePrice) " · ${copy.comparablePrice.format()}" else ""

/** How often a watched search may look again, and the words for each. */
val WATCH_INTERVALS: List<Pair<Int, String>> =
    listOf(30 to "30 min", 60 to "1 hour", 180 to "3 hours", 360 to "6 hours", 720 to "12 hours", 1440 to "a day")

/** A new notification rule from what the form holds; [words] is comma separated. */
fun newRule(maxPriceEur: Int?, condition: String?, words: String): NotificationSubfilter = NotificationSubfilter(
    id = "r" + Clock.System.now().toEpochMilliseconds().toString(36),
    maxPriceEur = maxPriceEur,
    condition = condition,
    mustContainAnyOf = words.split(",").map { it.trim() }.filter { it.isNotEmpty() },
)
