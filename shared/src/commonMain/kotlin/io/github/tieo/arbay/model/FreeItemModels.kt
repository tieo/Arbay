package io.github.tieo.arbay.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class FreeItemProfile(
    val description: String,
    val location: String? = null,   // city name or zip code for radius search
    val radiusKm: Int = 30,         // search radius in km (default 30km)
    val trackingEnabled: Boolean = false,  // background monitoring for new matches
)

@Serializable
enum class FeedbackAction { LOVE, LIKE, DISLIKE, PASS }

@Serializable
data class ListingFeedback(
    val listingId: String,
    val title: String,
    val action: FeedbackAction,
    // Rich metadata for history display
    val url: String? = null,
    val imageUrl: String? = null,
    val locationText: String? = null,
    val description: String? = null,
    val relevanceScore: Double? = null,
    // IDs of remaining items in the feed — server rescores them after retrain
    val remainingIds: List<String> = emptyList(),
)

@Serializable
data class FeedbackResponse(
    val ok: Boolean,
    val rescored: Map<String, Double> = emptyMap(),
)

@Serializable
data class FreeItemInsights(
    val embeddingAvailable: Boolean,
    val seenIds: List<String> = emptyList(),  // IDs already loved/disliked — hide on next open
)

/** A single feedback history entry for display in the Liked/Disliked tabs. */
@Serializable
data class FeedbackHistoryItem(
    val listingId: String,
    val title: String,
    val action: FeedbackAction,
    val url: String? = null,
    val imageUrl: String? = null,
    val locationText: String? = null,
    val description: String? = null,
    val relevanceScore: Double? = null,
    val timestamp: Instant? = null,
)

/** A new high-confidence match detected by background tracking. */
@Serializable
data class NewMatch(
    val listingId: String,
    val title: String,
    val url: String,
    val imageUrl: String? = null,
    val locationText: String? = null,
    val description: String? = null,
    val relevanceScore: Double? = null,
    val firstSeen: Instant,
)

/** A rejected (low-scored) item that the user can browse for false negative discovery. */
@Serializable
data class RejectedItem(
    val listingId: String,
    val title: String,
    val url: String,
    val imageUrl: String? = null,
    val locationText: String? = null,
    val description: String? = null,
    val relevanceScore: Double? = null,
    val firstSeen: Instant,
)

/** Aggregate statistics about free item feedback. */
@Serializable
data class FreeItemStats(
    val totalLoved: Int,
    val totalDisliked: Int,
    val totalSeen: Int,
    val embeddingAvailable: Boolean,
)

/**
 * When the app is allowed to interrupt.
 *
 * Two things are worth a notification, and they share one property: waiting costs you the thing.
 * A free item near you is gone in an hour. A listing priced well under what that search usually
 * costs is gone in a day. Everything else the app knows can be read when the app is opened, and
 * saying it twice is noise: what a saved search found is on the saved search, what a market
 * answered is in the markets view.
 *
 * Nothing here reaches a topic on a server. A notification is raised on the device the app runs
 * on, so it can be silenced where every other notification is silenced.
 */
@Serializable
data class NotificationSettings(
    /** How often the server looks, whether or not anything is allowed to interrupt. */
    val checkEveryMinutes: Int = 60,
    /** A free item near you scoring at least [freeItemScorePct] against your profile. */
    val freeItemAlerts: Boolean = true,
    val freeItemScorePct: Int = 85,
    /** A listing in a watched search priced at or under [dealUnderMedianPct] of that search's
     *  median, counted only where enough listings carry a price for a median to mean anything. */
    val dealAlerts: Boolean = true,
    val dealUnderMedianPct: Int = 75,
)

/**
 * One listing in a watched search priced far enough under that search's median to be worth
 * hearing about before the app is next opened. [underMedianPct] is how far under, so the
 * notification can say why it counts as a deal.
 */
@Serializable
data class DealMatch(
    val listingId: String,
    val searchName: String,
    val title: String,
    val url: String,
    val priceText: String,
    val underMedianPct: Int,
    val locationText: String? = null,
)

/** A listing that matched a notification subfilter someone set on a specific saved search —
 *  "auto-fetch this, and tell me when one shows up under €8000" rather than a silent count on the
 *  bookmark. Says which subfilter, by name, so the notification carries its own reason. */
@Serializable
data class SubfilterMatch(
    val listingId: String,
    val searchName: String,
    val subfilterName: String,
    val title: String,
    val url: String,
    val priceText: String? = null,
    val locationText: String? = null,
)

/** What a background poll found, for the device to raise notifications from. */
@Serializable
data class PollResult(
    val totalNew: Int = 0,
    // Items worth interrupting for: near you, free, and scoring above your threshold.
    val urgentMatches: List<NewMatch> = emptyList(),
    // Listings in watched searches priced under that search's median.
    val deals: List<DealMatch> = emptyList(),
    // Listings matching a notification subfilter someone set on a specific saved search.
    val subfilterMatches: List<SubfilterMatch> = emptyList(),
    val lastPollTime: Instant? = null,
)
