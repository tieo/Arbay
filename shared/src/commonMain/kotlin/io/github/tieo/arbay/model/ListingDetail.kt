package io.github.tieo.arbay.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * What a listing's own page says, beyond what its card on the results page carried.
 *
 * The card is a summary the market writes for a list: a price, a photo, a line of specs. The page
 * is the ad itself. Three things live only there and each of them was missing from a real screen —
 * the specs a filter needs (power, gearbox), the seller's own description (where a van's wheelbase
 * is written down, when it is written down at all), and where the thing stands (eBay says it on the
 * page and nowhere on the card).
 *
 * Fetched at most once per listing and cached, since none of it changes while the ad is up.
 */
@Serializable
data class ListingDetail(
    val vehicle: VehicleInfo? = null,
    /** The seller's own text, as the page shows it. */
    val description: String? = null,
    val location: Location? = null,
    /** Shipping as the page states it, where the card only said it ships: Kleinanzeigen's page
     *  names the cheapest carrier the seller allows ("+ Versand ab 6,19 €"). */
    val shipping: Shipping? = null,
    /** When the ad first went up, where only its page says it: mobile.de's results carry no date. */
    val listedAt: Instant? = null,
    /** How many reviews the dealer's stars rest on, where only the page has the whole number:
     *  a mobile.de result card counts some of them (12 where the page says 474). */
    val sellerReviews: Int? = null,
    /** Every photo of the ad's gallery, where the card carried only the first. */
    val photos: List<String> = emptyList(),
)

/**
 * Whether an ad can still be bought, as its own page says right now. Kleinanzeigen keeps a page up
 * after the seller deletes or pauses the ad and only drops it from search, so an ad that opens is
 * not necessarily for sale.
 */
@Serializable
enum class AdState { LIVE, PAUSED, DELETED }

/**
 * An ad as its own page has it right now, read for an offer about to be written: a seller changes
 * the price, pauses the ad or turns shipping off between a search and the message, and an offer
 * worked out from what the search saw went out above a lowered ask or to a pickup-only ad.
 */
@Serializable
data class AdNow(
    val state: AdState? = null,
    /** The asking price the page shows. */
    val price: Money? = null,
    val shipping: Shipping? = null,
)

