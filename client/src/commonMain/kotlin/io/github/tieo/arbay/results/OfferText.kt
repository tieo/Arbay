package io.github.tieo.arbay.results

import io.github.tieo.arbay.ImportRules
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.format
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.Location
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SellerProfile
import io.github.tieo.arbay.model.fact
import io.github.tieo.arbay.model.VehicleInfo
import io.github.tieo.arbay.model.importVat
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.round

/**
 * One offer as it reads in full: what the search card carried, with what its own page adds. The
 * page's vehicle facts fill the gaps the card left, its description replaces a shorter one, and its
 * place stands in where the card had none. [stock] is how many the page says are left ("3 left",
 * "over 10 left", "sold out"), where it says.
 */
data class OfferReading(val vehicle: VehicleInfo?, val description: String?, val place: Location?, val stock: String? = null, val soldBroken: Boolean = false, val seller: SellerProfile? = null)

fun readOffer(listing: Listing, fromItsPage: ListingDetail?): OfferReading {
    val vehicle = fromItsPage?.vehicle?.let { page ->
        listing.vehicle?.let { card -> card.copy(wheelbaseMm = card.wheelbaseMm ?: page.wheelbaseMm, verified = card.verified + page.verified) } ?: page
    } ?: listing.vehicle
    val description = fromItsPage?.description?.takeIf { it.length > (listing.description?.length ?: 0) } ?: listing.description
    val stock = fromItsPage?.available?.let { n -> if (fromItsPage.moreAvailable) "over $n left" else if (n == 0) "sold out" else if (n == 1) "last one" else "$n left" }
    return OfferReading(vehicle, description, listing.location ?: fromItsPage?.location, stock, fromItsPage?.soldBroken == true, fromItsPage?.seller)
}

/** The short facts of an offer, each one a few words: condition, how many are left, place,
 *  distance, age, how it is sold and delivered, and by whom. */
fun offerFacts(listing: Listing, page: OfferReading): List<String> = buildList {
    // An ad whose text says it is sold broken reads as one sold for parts, as the card would
    // have said had it carried the text.
    (if (page.soldBroken) Condition.PARTS_ONLY else listing.condition)?.let { add(it.label) }
    page.stock?.let(::add)
    page.place?.let { loc -> listOfNotNull(loc.zip, loc.city ?: loc.raw ?: loc.country).joinToString(" ").takeIf { it.isNotBlank() }?.let(::add) }
    listing.distanceKm?.let { add("${it.toInt()} km away") }
    listing.listingDate?.let { add("posted ${it.toLocalDateTime(TimeZone.currentSystemDefault()).date}") }
    if (listing.saleType == SaleType.AUCTION) add(listing.bidCount?.let { "$it bids" } ?: "auction")
    listing.shipping?.cost?.takeIf { it.amount > 0 }?.let { add("delivery ${it.format()}") }
    if (listing.shipping?.free == true) add("free delivery")
    if (listing.negotiable) add("negotiable")
    val profile = page.seller
    if (profile != null) add(profile.fact)
    else listing.seller?.takeIf { !it.name.isNullOrBlank() }?.let { s -> add("sold by ${s.name}" + (s.rating?.let { " · $it★" } ?: "")) }
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

