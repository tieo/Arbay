package io.github.tieo.arbay.results

import io.github.tieo.arbay.model.SavedSearchStatus
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
    val markets = "${product.searchQuery.platforms.size} platforms"
    val termDiffers = !product.searchQuery.text.trim().equals(product.name.trim(), ignoreCase = true)
    return if (termDiffers) "${product.searchQuery.text}  ·  $markets" else markets
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
