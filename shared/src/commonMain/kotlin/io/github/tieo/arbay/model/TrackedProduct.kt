package io.github.tieo.arbay.model

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
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
    /**
     * Watch the market until then and tell nothing: the search learns the lowest price anything
     * this rule passes is offered at ([bestSeenEur]), and from then on a find is only worth a push
     * when it costs less than that. Buying the first good offer after looking at the first 37 % of
     * the time there is, without buying, is the best a buyer who cannot go back can do.
     */
    val learnUntil: Instant? = null,
    /** The lowest landed price, whole euros, of what the rule passed while it was learning. */
    val bestSeenEur: Int? = null,
    /** The offer that price was learned from, so the bar can be checked against its ad, and
     *  learned again from the market when that offer is put away as a fake or a scam. */
    val bestSeenListingId: String? = null,
)

/** Whether the rule is still learning the market at [now] rather than telling about finds. */
fun NotificationSubfilter.learning(now: Instant): Boolean = learnUntil != null && now < learnUntil

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
        if (learnUntil != null) add(
            "cheaper than the best until ${learnUntil.toString().take(10)}" + (bestSeenEur?.let { " (€$it so far)" } ?: ""),
        )
    }
    return if (parts.isEmpty()) "Any find in this search" else parts.joinToString(" · ")
}

/**
 * What to call this subfilter when something needs one string — its own name, or its criteria when
 * it has nothing that reads as a name.
 *
 * A name of "." passes any test for being non-empty and says nothing: the notification it produced
 * read ". · parkettschleifmaschine". Naming one is optional, and a placeholder typed to get past a
 * field is the same as not naming it, so anything without a letter or a digit falls back to the
 * criteria, which always say something true.
 */
val NotificationSubfilter.displayName: String get() =
    name.takeIf { n -> n.any { it.isLetterOrDigit() } } ?: summaryText()

@Serializable
data class TrackedProduct(
    val id: String,
    val name: String,
    val searchQuery: SearchQuery,
    val identifiers: ProductIdentifier = ProductIdentifier(),
    val createdAt: Instant,
    val autoFetch: AutoFetchSettings = AutoFetchSettings(),
    val notificationSubfilters: List<NotificationSubfilter> = emptyList(),
    val fitAlert: FitAlertSettings = FitAlertSettings(),
)

/**
 * Tell the user about a new offer that fits the search as a whole, rather than one that passes
 * every filter. The search's vehicle criteria are read as wishes and weighed against each other
 * (see the server's FitScore), so a van that misses one wish by a little and fits the rest still
 * counts, and one that scrapes past every filter with several small misses does not.
 *
 * [home] is where drive times are measured from, as a postcode and place ("70173
 * Stuttgart"); [softDriveHours] is a drive the user would happily make, past which a fit counts for
 * less rather than not at all.
 *
 * A new offer is sent when it would rank among the [topN] best of everything the search finds right
 * now, and never twice. A rank rather than a fixed score, because a score means nothing on its own:
 * the van the user called perfect scored 0.74 on price and distance, below a threshold that would
 * have let through offers whose size is not even stated. [minScore] only keeps a thin week from
 * pushing whatever happens to be best of very little.
 */
@Serializable
data class FitAlertSettings(
    val enabled: Boolean = false,
    val topN: Int = 10,
    val minScore: Double = 0.6,
    val home: String? = null,
    val softDriveHours: Double = 4.0,
)

/** How often a watched search may look again, and the words for each. */
val WATCH_INTERVALS: List<Pair<Int, String>> =
    listOf(30 to "30 min", 60 to "1 hour", 180 to "3 hours", 360 to "6 hours", 720 to "12 hours", 1440 to "day")

/**
 * A new notification rule from what the form holds; [words] is comma separated. [minPriceEur]
 * keeps out what costs too little to be the thing: a bundle priced per piece, an empty box, a bid.
 * With [learnDays], the rule watches the market that long first and then tells only about finds
 * below the best seen.
 */
fun newRule(maxPriceEur: Int?, condition: String?, words: String, learnDays: Int? = null, minPriceEur: Int? = null): NotificationSubfilter {
    val now = Clock.System.now()
    return NotificationSubfilter(
        id = "r" + now.toEpochMilliseconds().toString(36),
        minPriceEur = minPriceEur,
        maxPriceEur = maxPriceEur,
        condition = condition,
        mustContainAnyOf = words.split(",").map { it.trim() }.filter { it.isNotEmpty() },
        learnUntil = learnDays?.takeIf { it > 0 }?.let { now + it.days },
    )
}
