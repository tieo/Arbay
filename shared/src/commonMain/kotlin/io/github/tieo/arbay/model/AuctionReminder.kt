package io.github.tieo.arbay.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * A standing request to be told about one auction before it ends.
 *
 * Every auction stays in the results whatever time is left on it. This is the other half: the
 * moment worth interrupting for is a chosen stretch before the end, which is when a bid can still
 * be made, and it is set per auction rather than as a rule over all of them.
 */
@Serializable
data class AuctionReminder(
    val listingId: String,
    val title: String,
    val url: String,
    val endsAt: Instant,
    /** How long before the end to say so. */
    val leadMinutes: Int = 30,
    val priceText: String? = null,
    val platformName: String? = null,
)

/** The lead times offered, and what they are called. Kept with the model so the app and whatever
 *  reads a stored reminder agree on what a number means. */
val AUCTION_LEAD_CHOICES: List<Pair<Int, String>> = listOf(
    10 to "10 minutes before",
    30 to "30 minutes before",
    60 to "an hour before",
    240 to "4 hours before",
    1440 to "a day before",
)
