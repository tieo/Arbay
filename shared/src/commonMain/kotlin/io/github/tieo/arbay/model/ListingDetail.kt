package io.github.tieo.arbay.model

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
)
