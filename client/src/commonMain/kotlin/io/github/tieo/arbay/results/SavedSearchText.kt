package io.github.tieo.arbay.results

import io.github.tieo.arbay.model.SavedSearchStatus
import io.github.tieo.arbay.model.TopOfferState
import io.github.tieo.arbay.model.TrackedProduct
import kotlin.time.Clock

/** How long ago something happened, in the coarsest unit that still says it. */
fun ago(millis: Long): String {
    val minutes = ((Clock.System.now().toEpochMilliseconds() - millis) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}

/** What a saved search asks and where, in one line: its own term only when that says more than
 *  its name, since the two are usually the same words. */
fun savedSearchSubtitle(product: TrackedProduct): String {
    val markets = product.searchQuery.platforms.size.let { if (it == 1) "1 market" else "$it markets" }
    val termDiffers = !product.searchQuery.text.trim().equals(product.name.trim(), ignoreCase = true)
    return if (termDiffers) "${product.searchQuery.text}  ·  $markets" else markets
}

/** How many of the search's best offers it is losing, and how many its last run ruled out; null when none. */
fun lostLabel(status: SavedSearchStatus): String? {
    val lost = status.missingTopOffers.count { it.state == TopOfferState.LOST }
    val ruledOut = status.missingTopOffers.count { it.state == TopOfferState.RULED_OUT }
    return listOfNotNull(
        lost.takeIf { it > 0 }?.let { "$it of its best offers no longer found" },
        ruledOut.takeIf { it > 0 }?.let { "$it of its best offers ruled out" },
    ).joinToString(", ").ifEmpty { null }
}

/** When a saved search last ran, so an empty result reads as "nothing new" rather than as a search
 *  that never happened. */
fun watchLabel(status: SavedSearchStatus): String {
    val ran = status.lastRunAtMillis?.let { ago(it) }
    return when {
        !status.watched -> "not watched"
        ran != null -> "checked $ran"
        else -> "watched, not run yet"
    }
}
