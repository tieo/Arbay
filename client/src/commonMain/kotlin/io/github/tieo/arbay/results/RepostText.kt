package io.github.tieo.arbay.results

import io.github.tieo.arbay.model.AdDraft
import io.github.tieo.arbay.model.nextRepost
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Where a posted ad stands with going up again: why it last did not, or the day it next does
 *  ("next on 3 Nov"); null where it stays as posted or is not posted yet. */
fun repostState(draft: AdDraft): String? {
    if (draft.postedAdId == null || draft.repostEveryDays == null) return null
    draft.repostProblem?.let { return it }
    val next = draft.nextRepost()?.toLocalDateTime(TimeZone.currentSystemDefault())?.date ?: return null
    val month = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[next.month.ordinal]
    return "next on ${next.day} $month"
}
