package io.github.tieo.arbay.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * A bid on an eBay auction that Arbay places in the auction's last seconds, so it does not drive the
 * price up before. [allInEur] is the most the user pays with shipping, [maxEur] the most the bid
 * goes to: [allInEur] less the auction's shipping. Neuland places it only while eBay's own review
 * of the bid reads back [maxEur] and totals no more than [allInEur], and only one bid stands at a
 * time.
 */
@Serializable
data class AuctionBid(
    val listingId: String,
    val url: String,
    val title: String,
    val endsAt: Instant,
    val allInEur: Double,
    val maxEur: Double,
    val shippingEur: Double,
    val state: AuctionBidState = AuctionBidState.WAITING,
    val asked: Instant,
    /** When the bid was confirmed on eBay. */
    val placedAt: Instant? = null,
    /** What eBay said once the bid was confirmed, in its words. */
    val outcome: String? = null,
    /** Why it was not placed, or why its auction's outcome is not settled. */
    val problem: String? = null,
    /** When the outcome of its ended auction was last asked of eBay. */
    val checkedAt: Instant? = null,
    /** For a bid not placed: Neuland keeps nothing of it, so it holds no later bid back. */
    val cleared: Boolean = false,
)

enum class AuctionBidState {
    /** Waiting for the auction's last minutes. */
    WAITING,
    /** Being placed now. */
    PLACING,
    /** Confirmed on eBay; the auction has not ended. */
    PLACED,
    /** Tried and not confirmed, [AuctionBid.problem] says why. */
    NOT_PLACED,
    /** Its turn came while another bid stood, or after the auction had ended. */
    SKIPPED,
    /** The auction it was placed on has ended and its outcome is not settled yet; it blocks every
     *  later bid, as it may have been won. */
    ENDED,
    /** Its auction ended without it winning, as eBay's lists show: the next bid may go out. */
    LOST,
    /** Its auction was won: it is among the account's purchases on eBay, and no later bid goes out. */
    WON,
    CANCELLED,
}

/** A bid asked for: the auction and the most the user pays for it with shipping. */
@Serializable
data class AuctionBidRequest(
    val listingId: String,
    val url: String,
    val title: String,
    val endsAt: Instant,
    val shippingEur: Double,
    val allInEur: Double,
)

/**
 * eBay's review of a bid as Neuland read it: the auction's bid so far, the least eBay takes next,
 * the bid as eBay reads it back, shipping and the total it comes to, and whether it was [placed].
 */
@Serializable
data class BidReview(
    val biddable: Boolean,
    val currentEur: Double? = null,
    val bids: Int? = null,
    val leastEur: Double? = null,
    val maxEur: Double? = null,
    val shippingEur: Double? = null,
    val totalEur: Double? = null,
    val placed: Boolean = false,
    /** Whether eBay's own list of running bids showed the auction after the bid was confirmed. */
    val standing: Boolean? = null,
    val outcome: String? = null,
    val problem: String? = null,
)
