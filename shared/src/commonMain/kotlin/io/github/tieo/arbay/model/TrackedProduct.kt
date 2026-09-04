package io.github.tieo.arbay.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ProductIdentifier(
    val gtins: List<String> = emptyList(),
    val mpn: String? = null,
)

/** Whether a saved search re-runs itself in the background, and how often. Off by default: a
 *  bookmark is a search someone chose to keep, not one they asked to be crawled on a schedule —
 *  those are different decisions, and a search that has never been told to watch itself should
 *  never crawl on its own. */
@Serializable
data class AutoFetchSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 360,
)

/** A named condition on top of a saved search's own criteria: when a listing this search finds
 *  also satisfies this, it is worth a push notification, not just a count on the bookmark. A
 *  search with 25 platforms and no subfilter finding "6 new" says nothing about whether any of
 *  the 6 are worth looking at now; a subfilter says exactly that.
 *
 *  [condition] mirrors the plain New/Used/Any split the results screen already filters by
 *  ("NEW" / "USED" / null), not the full [Condition] enum — the same three-way choice the rest of
 *  the app shows, not a second, finer-grained one someone has to learn separately. */
@Serializable
data class NotificationSubfilter(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val minPriceEur: Int? = null,
    val maxPriceEur: Int? = null,
    val condition: String? = null,
    // A listing's title must contain at least one of these (besides the search's own text) —
    // empty means the search's own text is already specific enough.
    val mustContainAnyOf: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
)

@Serializable
data class TrackedProduct(
    val id: String,
    val name: String,
    val searchQuery: SearchQuery,
    val identifiers: ProductIdentifier = ProductIdentifier(),
    val createdAt: Instant,
    val autoFetch: AutoFetchSettings = AutoFetchSettings(),
    val notificationSubfilters: List<NotificationSubfilter> = emptyList(),
)
