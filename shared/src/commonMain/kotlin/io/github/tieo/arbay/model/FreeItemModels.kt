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

/** Notification settings for free item monitoring. */
@Serializable
data class NotificationSettings(
    val pollIntervalMinutes: Int = 60,          // how often to crawl (default 1h)
    val digestEnabled: Boolean = true,           // periodic digest of >threshold items
    val digestIntervalHours: Int = 24,           // daily=24, bidaily=48, etc.
    val digestThresholdPct: Int = 50,            // items above this % in digest
    val urgentEnabled: Boolean = true,            // instant notification for very high matches
    val urgentThresholdPct: Int = 90,            // threshold for instant notification
    val novelEnabled: Boolean = true,             // notify about novel/rare items
    val novelSimilarityThreshold: Double = 0.3,  // max similarity to known items (lower = more novel)
)

/** Result of a background poll — summary for client-side notification. */
@Serializable
data class PollResult(
    val totalNew: Int = 0,
    val urgentMatches: List<NewMatch> = emptyList(),   // items above urgent threshold
    val digestMatches: List<NewMatch> = emptyList(),   // items above digest threshold
    val novelItems: List<NewMatch> = emptyList(),      // items unlike anything seen
    val lastPollTime: Instant? = null,
)
