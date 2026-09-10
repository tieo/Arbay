package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * A listing a market returned that the search removed before showing anything, and the reason.
 *
 * The search drops far more than the reader's own filters do, and it used to do it invisibly: a
 * market's whole answer could vanish after its cards had already streamed in, with nothing on
 * screen to say so. Every drop is carried back to the app instead, so a filter that is wrong about
 * a listing is something the reader can see rather than something they never learn about.
 */
@Serializable
data class DroppedListing(
    val listing: Listing,
    val reason: DropReason,
)

@Serializable
enum class DropReason {
    /** Not one listing the market sent carries a word of the search, so it answered something
     *  else and there is nothing in its answer to filter. */
    MARKET_IGNORED_SEARCH,

    /** The title names none of the words searched for. */
    OFF_TARGET,

    /** A part or add-on made for the thing, not the thing. */
    ACCESSORY,

    /** A machine of its own with the thing searched for built into it. */
    BUILT_INTO_A_DEVICE,

    /** Something the thing uses up: sanding paper, dust bags, filters. */
    CONSUMABLE,

    /** Someone asking to buy one, or a job ad, not an offer to sell. */
    WANTED_AD,

    /** Offered to hire, at a daily rate that is not a purchase price. */
    RENTAL,

    /** A placeholder title ("Neues Angebot") or a bulk lot of many units. */
    NOT_A_SINGLE_OFFER,

    /** A price the scraper read wrong — digits from several fields run together. */
    IMPLAUSIBLE_PRICE,
}

/** What the app writes next to a dropped listing. */
val DropReason.label: String
    get() = when (this) {
        DropReason.MARKET_IGNORED_SEARCH -> "market ignored the search"
        DropReason.OFF_TARGET -> "off target"
        DropReason.ACCESSORY -> "accessory"
        DropReason.BUILT_INTO_A_DEVICE -> "inside another device"
        DropReason.CONSUMABLE -> "consumable"
        DropReason.WANTED_AD -> "wanted ad"
        DropReason.RENTAL -> "for rent"
        DropReason.NOT_A_SINGLE_OFFER -> "not one offer"
        DropReason.IMPLAUSIBLE_PRICE -> "unreadable price"
    }
