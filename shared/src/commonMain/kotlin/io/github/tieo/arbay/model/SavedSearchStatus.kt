package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * What a saved search has been doing since it was last opened.
 *
 * Saved searches are re-crawled on a schedule and alerts fire when something new turns up, and none
 * of that reached any screen: a saved search looked identical whether it was being watched, had
 * just found six new listings, or had never run at all.
 */
@Serializable
data class SavedSearchStatus(
    val productId: String,
    // Whether the server is re-running this search on its own.
    val watched: Boolean = false,
    // When it last ran, epoch milliseconds, or null if it never has.
    val lastRunAtMillis: Long? = null,
    // Listings found since the search was last opened.
    val newSinceOpened: Int = 0,
    // Which listings those are, so opening the search can show just them instead of only counting
    // them. Carried with the count rather than fetched separately: the count is this list's size.
    val newListingIds: List<String> = emptyList(),
    // Offers that once ranked in this search's top ten and its last run did not return: lost while
    // their ad is up or its market cannot say, and ruled out by one of the search's own rules on
    // the run that first did so, with the rule named. A best offer leaves silently in neither way.
    val missingTopOffers: List<TopOffer> = emptyList(),
    // Offers this search keeps finding that have come down in price since it was last opened, each
    // from what it cost when first seen dearer to what it costs now.
    val priceDrops: List<PriceDrop> = emptyList(),
)

/**
 * An offer a saved search found again for less than on an earlier run. [fromEur] is the landed
 * price before the first drop since the search was last opened, so an offer lowered twice reads
 * as the whole way down; [toEur] is what it costs as the latest run found it; [at] is when it
 * dropped last.
 */
@Serializable
data class PriceDrop(
    val listingId: String,
    val url: String,
    val title: String,
    val fromEur: Double,
    val toEur: Double,
    val at: kotlin.time.Instant,
)

/** Whether a saved search's last run still returned an offer it once ranked among its best. */
@Serializable
enum class TopOfferState {
    FOUND,
    /** Not returned, its ad up or its market unable to say: the search is losing it. */
    LOST,
    /** Returned by its market and removed by a rule of the search, named in [TopOffer.ruledOutBy]. */
    RULED_OUT,
    /** Not returned, and its page says it was taken down. */
    GONE,
}

/** An offer that ranked in a saved search's top ten, as it was then and where it stands now. */
@Serializable
data class TopOffer(
    val listingId: String,
    val url: String,
    val title: String,
    val priceEur: Double,
    val bestScore: Double,
    val firstTopAt: kotlin.time.Instant,
    val lastSeenAt: kotlin.time.Instant,
    val state: TopOfferState,
    val checkedAt: kotlin.time.Instant? = null,
    /** What the ad's own page said when it was last looked for, where the market says. */
    val adState: AdState? = null,
    /** The search's rule that removed it, "vehicle criteria: mileage", while [state] is RULED_OUT. */
    val ruledOutBy: String? = null,
)
