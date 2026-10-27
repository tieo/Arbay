package io.github.tieo.arbay.results

import io.github.tieo.arbay.model.AdDraft
import io.github.tieo.arbay.model.nextRepost
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/** Where a posted ad stands with going up again: why it last did not, or the day it next does
 *  ("next on 3 Nov", "next within the hour"); null where it stays as posted or is not posted yet. */
fun repostState(draft: AdDraft): String? {
    if (draft.postedAdId == null || draft.repostEveryDays == null) return null
    draft.repostProblem?.let { return it }
    val due = draft.nextRepost() ?: return null
    // Arbay looks every hour, so a day already past is the next hour.
    if (due <= Clock.System.now()) return "next within the hour"
    val next = due.toLocalDateTime(TimeZone.currentSystemDefault()).date
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[next.month.ordinal]
    return "next on ${next.day} $month"
}
