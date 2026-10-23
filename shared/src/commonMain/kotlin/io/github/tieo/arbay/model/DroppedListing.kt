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
    /** Which of several things behind one reason took it — the criterion a vehicle failed
     *  ("mileage", "wheelbase"), or the word that was blocked. "The search removed it" is not an
     *  answer anyone can check; "its mileage" is. */
    val detail: String? = null,
)

/**
 * How many of a market's offers one reason took, counted over everything the search removed. A
 * search where somebody waits is sent only some of the removed offers themselves, so the number a
 * reason took is carried on its own: a reader counting the sample would see a filter that took
 * four hundred vans as one that took none.
 */
@Serializable
data class DropCount(
    val reason: DropReason,
    val detail: String? = null,
    val count: Int,
)

/** Every removed offer counted per reason and detail, most first. */
fun List<DroppedListing>.dropCounts(): List<DropCount> =
    groupingBy { it.reason to it.detail }.eachCount()
        .map { (key, n) -> DropCount(key.first, key.second, n) }
        .sortedByDescending { it.count }

/**
 * At most [limit] of [dropped], in the market's order, with every reason and detail represented:
 * taken round the groups one offer at a time, so four hundred offers removed for their mileage
 * cannot crowd the eighty slots out of the twelve removed for a blocked word, nor the other way.
 */
fun sampleDropped(dropped: List<DroppedListing>, limit: Int): List<DroppedListing> {
    if (dropped.size <= limit) return dropped
    val queues = dropped.indices.groupBy { dropped[it].reason to dropped[it].detail }.values.map { ArrayDeque(it) }
    val picked = BooleanArray(dropped.size)
    var n = 0
    while (n < limit) queues.forEach { q -> if (n < limit) q.removeFirstOrNull()?.let { picked[it] = true; n++ } }
    return dropped.filterIndexed { i, _ -> picked[i] }
}

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

    /** One listing offering a row of sizes, priced at the smallest of them. */
    ONE_OF_SEVERAL_SIZES,

    /** The search names a size and the listing states only other ones: a 1.2TB drive found by a
     *  search for 12TB, whose words match once the comma is gone. */
    ANOTHER_SIZE,

    /** Something the thing uses up: sanding paper, dust bags, filters. */
    CONSUMABLE,

    /** Someone asking to buy one, or a job ad, not an offer to sell. */
    WANTED_AD,

    /** Offered to hire, at a daily rate that is not a purchase price. */
    RENTAL,

    /** A placeholder title ("Neues Angebot") or a bulk lot of many units. */
    NOT_A_SINGLE_OFFER,

    /** Further from where the search is centred than it reaches. */
    TOO_FAR,

    /** One of the vehicle criteria this search carries — its mileage, year, power, size — or the
     *  guard that keeps parts out of a search for a whole vehicle. */
    VEHICLE_CRITERIA,

    /** Carries one of the words the reader blocked. Its own reason, because reading it as "not one
     *  offer" put twelve real vans under a heading that says the market sent junk. */
    BLOCKED_WORD,

    /** Sold by a dealer the reader blocked. The user's own decision about who they buy from, kept
     *  apart from everything said about the listing itself. */
    BLOCKED_DEALER,

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
        DropReason.ONE_OF_SEVERAL_SIZES -> "priced at a smaller size"
        DropReason.ANOTHER_SIZE -> "another size"
        DropReason.CONSUMABLE -> "consumable"
        DropReason.WANTED_AD -> "wanted ad"
        DropReason.RENTAL -> "for rent"
        DropReason.NOT_A_SINGLE_OFFER -> "not one offer"
        DropReason.TOO_FAR -> "too far away"
        DropReason.VEHICLE_CRITERIA -> "a vehicle criterion"
        DropReason.BLOCKED_WORD -> "a word you blocked"
        DropReason.BLOCKED_DEALER -> "a dealer you blocked"
        DropReason.IMPLAUSIBLE_PRICE -> "unreadable price"
    }
