package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * How a result list is ordered.
 *
 * Lives beside the search it belongs to rather than in the view model, because the order someone
 * chose is part of the search they saved: reopening a saved search restores it.
 */
@Serializable
enum class SortMode(val label: String, val forDrives: Boolean = false, val forOwnCost: Boolean = false) {
    BEST_MATCH("Best match"),
    PRICE_ASC("Price: low to high"),
    PRICE_DESC("Price: high to low"),
    NEAREST("Nearest first"),
    NEWEST("Newest first"),
    CENTS_PER_GB("Price per GB", forDrives = true),
    LONGEST_LIFE("Longest expected life", forDrives = true),
    COST_TO_OWN("Cheapest to own", forOwnCost = true);

    companion object
}

/** The orders a result list offers: those for drives and for a vehicle's cost to own only where
 *  its offers carry what they order by. */
fun SortMode.Companion.offered(listings: List<Listing>): List<SortMode> = SortMode.entries.filter { mode ->
    (!mode.forDrives || listings.any { it.drive != null }) && (!mode.forOwnCost || listings.any { it.ownCost != null })
}
