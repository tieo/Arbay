package io.github.tieo.arbay.chat

import io.github.tieo.arbay.model.Conversation
import io.github.tieo.arbay.model.OutgoingMessage
import io.github.tieo.arbay.model.OutgoingState
import io.github.tieo.arbay.results.ago
import kotlin.time.Clock

/** Where the user stands with one offer's seller, for its row in the results; null when nowhere. */
fun sellerMark(conversation: Conversation?, outgoing: OutgoingMessage?): String? {
    if (outgoing != null && outgoing.state != OutgoingState.SENT && outgoing.state != OutgoingState.CANCELLED) {
        return outgoingLabel(outgoing)
    }
    conversation ?: return outgoing?.takeIf { it.state == OutgoingState.SENT }?.let { "asked ${ago(it.sendAt.toEpochMilliseconds())}" }
    val answered = conversation.lastText != null && conversation.unread > 0
    return when {
        answered -> "replied · ${conversation.unread} new"
        else -> conversation.lastAt?.let { "asked ${ago(it.toEpochMilliseconds())}" } ?: "asked"
    }
}

/** A message on its way, as a few words. */
fun outgoingLabel(m: OutgoingMessage): String = when (m.state) {
    OutgoingState.WAITING -> {
        val seconds = (m.sendAt - Clock.System.now()).inWholeSeconds
        if (seconds <= 5) "sending now" else if (seconds < 90) "sending in $seconds s" else "sending in ${seconds / 60} min"
    }
    OutgoingState.SENDING -> "sending now"
    OutgoingState.SENT -> "sent ${ago(m.sendAt.toEpochMilliseconds())}"
    OutgoingState.FAILED -> "not sent: ${m.error ?: "Kleinanzeigen refused"}"
    OutgoingState.CANCELLED -> "called off"
}

/** The market's own page for a Kleinanzeigen ad, from Arbay's listing id. */
fun kleinanzeigenAdUrl(listingId: String): String? =
    listingId.substringAfter("KLEINANZEIGEN:", "").takeIf { it.isNotEmpty() }?.let { "https://www.kleinanzeigen.de/s-anzeige/$it" }
