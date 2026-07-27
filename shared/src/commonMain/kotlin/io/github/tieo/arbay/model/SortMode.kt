package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * How a result list is ordered.
 *
 * Lives beside the search it belongs to rather than in the view model, because the order someone
 * chose is part of the search they saved: reopening a saved search restores it.
 */
@Serializable
enum class SortMode(val label: String) {
    BEST_MATCH("Best match"),
    PRICE_ASC("Price: low to high"),
    PRICE_DESC("Price: high to low"),
    NEAREST("Nearest first"),
    NEWEST("Newest first"),
}
