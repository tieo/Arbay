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
    // Optional: the criteria themselves ([summaryText]) already say what the subfilter is,
    // so naming one is for telling apart two similar subfilters on the same search, not required
    // to have one at all.
    val name: String = "",
    val enabled: Boolean = true,
    val minPriceEur: Int? = null,
    val maxPriceEur: Int? = null,
    val condition: String? = null,
    // A listing's title must contain at least one of these (besides the search's own text) —
    // empty means the search's own text is already specific enough.
    val mustContainAnyOf: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
)

/** The criteria in one line, in the order they'd narrow a listing down: price, condition, words.
 *  Used as the notification/list display whenever [NotificationSubfilter.name] is blank — the
 *  criteria already say what the subfilter is, so an unnamed one still reads as something specific
 *  rather than "notification subfilter #2". */
fun NotificationSubfilter.summaryText(): String {
    val parts = buildList {
        when {
            minPriceEur != null && maxPriceEur != null -> add("€$minPriceEur–$maxPriceEur")
            maxPriceEur != null -> add("up to €$maxPriceEur")
            minPriceEur != null -> add("€$minPriceEur+")
        }
        when (condition) {
            "NEW" -> add("new only")
            "USED" -> add("used only")
        }
        if (mustContainAnyOf.isNotEmpty()) add("has " + mustContainAnyOf.joinToString(" or "))
        if (excludeKeywords.isNotEmpty()) add("not " + excludeKeywords.joinToString(", "))
    }
    return if (parts.isEmpty()) "Any find in this search" else parts.joinToString(" · ")
}

/** What to call this subfilter when something needs one string — its own name, or its criteria
 *  when it was never given one. */
val NotificationSubfilter.displayName: String get() = name.ifBlank { summaryText() }

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
