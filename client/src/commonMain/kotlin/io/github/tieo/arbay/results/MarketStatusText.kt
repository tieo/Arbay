package io.github.tieo.arbay.results

import io.github.tieo.arbay.model.PlatformSearchStatus
import io.github.tieo.arbay.viewmodel.PlatformStatus

/**
 * What happened at one market, in as few words as carry it.
 *
 * A market that worked says nothing: the count beside its name is the whole story. Only a market
 * that gave you less than it could explains itself, and then in one line. What each market can and
 * cannot publish used to be printed on every row, four lines of it, which turned a list you pick
 * from into a wall of prose nobody reads.
 */
fun PlatformStatus.saidWhat(kept: Int, hiddenByWords: Boolean = false): String? = when (status) {
    // A market that was not part of this crawl says so, and says how to have it asked.
    PlatformSearchStatus.PENDING -> fetchStage?.let(::fetchStageWords) ?: "waiting its turn"
    PlatformSearchStatus.SEARCHING -> fetchStage?.let(::fetchStageWords) ?: "being asked"
    PlatformSearchStatus.CAPTCHA -> "asked for a captcha instead of answering"
    // A market that was still sending when it was given up on has usually sent some of it. Saying
    // only that it was too slow, beside a count of what it managed, reads as a contradiction.
    PlatformSearchStatus.TIMEOUT ->
        if (kept > 0) "too slow; this is what it managed before it was given up on"
        else "too slow, given up on"
    PlatformSearchStatus.IP_BLOCKED -> "blocked us"
    PlatformSearchStatus.BLOCKED -> "rate limited, cooling down"
    PlatformSearchStatus.ERROR ->
        if (kept > 0) shortError(error)?.let { "$it; this is what it sent first" } ?: "failed partway"
        else shortError(error) ?: "failed"
    // "Nothing here" is about the market. What your own blocked words hid is about you, and the
    // two were the same sentence.
    PlatformSearchStatus.DONE -> when {
        kept > 0 -> null
        hiddenByWords -> "everything it sent is hidden by your blocked words"
        else -> "nothing here"
    }
}

/** The first line of a failure, cut to a length someone reads rather than skips. A crawler failure
 *  arrives as whatever the underlying library threw, which for the browser-driven markets is a
 *  stack trace hundreds of lines long. */
private fun shortError(error: String?): String? {
    val first = error?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    return if (first.length <= 60) first else first.take(57) + "…"
}

/** Whether this market's answer is the kind worth marking: refused, timed out, failed. */
fun PlatformStatus.isProblem(): Boolean = when (status) {
    PlatformSearchStatus.DONE, PlatformSearchStatus.PENDING, PlatformSearchStatus.SEARCHING -> false
    else -> true
}

/** How the asking is going, in one line: how many markets answered, how many could not be asked,
 *  and how many wait for a captcha someone has to solve. */
fun askingSummary(loading: Boolean, total: Int, completed: Int, statuses: List<PlatformStatus>): String {
    val captcha = statuses.count { it.status == PlatformSearchStatus.CAPTCHA && it.captchaUrl != null }
    val failed = statuses.count { it.isProblem() }
    return when {
        loading && total > 0 -> "$completed of $total markets have answered"
        loading -> "Asking the markets"
        else -> "${statuses.count { it.status == PlatformSearchStatus.DONE }} of ${statuses.size} markets answered"
    } + (if (failed > 0 && !loading) " · $failed could not be asked" else "") +
        (if (captcha > 0) " · $captcha waiting for a captcha" else "")
}

/** How many markets wait for a captcha that can be solved in place. */
fun List<PlatformStatus>.captchaCount(): Int = count { it.status == PlatformSearchStatus.CAPTCHA && it.captchaUrl != null }
