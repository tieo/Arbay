package io.github.tieo.arbay.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * A seller the user does not want to see offers from, on one market.
 *
 * Matched by the market's own account number where its cards carry one, since a dealer's name is
 * written differently from ad to ad ("bhg autohandelsgesellschaft mbH", "bhg Autohandelsgesellschaft
 * mbh") and two dealers can share one. An entry without a number matches by name instead, which is
 * how the same dealer is blocked on a second market: as an entry of its own for that market, never
 * by guessing that a name on one market is the dealer from another.
 */
@Serializable
data class BlockedDealer(
    val platform: PlatformId,
    /** The market's number for the seller; null for an entry that matches by name. */
    val sellerId: String? = null,
    val name: String,
    val blockedAt: Instant,
    /** Why, in the user's own words, where they gave a reason. */
    val reason: String? = null,
) {
    fun matches(listing: Listing): Boolean {
        if (listing.platformId != platform) return false
        val seller = listing.seller ?: return false
        return if (sellerId != null) seller.id == sellerId
        else seller.name?.let { dealerNameKey(it) == dealerNameKey(name) } == true
    }

    companion object {
        /** The entry that blocks this listing's seller, or null where its card names no seller. */
        fun of(listing: Listing, at: Instant, reason: String? = null): BlockedDealer? {
            val seller = listing.seller ?: return null
            if (seller.id == null && seller.name.isNullOrBlank()) return null
            return BlockedDealer(listing.platformId, seller.id, seller.shownName, at, reason?.takeIf { it.isNotBlank() })
        }
    }
}

/** The blocked dealer that took this listing, if any. */
fun List<BlockedDealer>.blocking(listing: Listing): BlockedDealer? =
    if (listing.seller == null) null else firstOrNull { it.matches(listing) }

/** A dealer's name reduced to its letters and digits, lowercase, so case, spacing and punctuation
 *  do not make one name two. */
fun dealerNameKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
