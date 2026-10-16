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
    // Offers that once ranked in this search's top ten and its last run did not return, while
    // their ad is up or its market cannot say: the search is losing them.
    val lostTopOffers: List<TopOffer> = emptyList(),
)

/** Whether a saved search's last run still returned an offer it once ranked among its best. */
@Serializable
enum class TopOfferState { FOUND, LOST, GONE }

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
)
