package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * Something that changed on the server, told to every open app at once, so a rename made on the
 * phone, in the browser or by an assistant through MCP shows on every screen without a reload.
 */
@Serializable
data class LiveEvent(
    val kind: LiveKind,
    /** For [LiveKind.SHOW]: the place in the app to open, as a path (see Route.path). */
    val path: String? = null,
)

/** What one of the user's devices shows right now, as the app there reports it. */
@Serializable
data class OnScreen(val device: String, val path: String, val at: kotlin.time.Instant? = null)

@Serializable
enum class LiveKind {
    /** Saved searches changed. */
    SAVED_SEARCHES,
    /** The shared state changed: lately run searches, hidden offers, the look. */
    STATE,
    /** Conversations, the outbox or the texts changed. */
    CHAT,
    /** Open this place on the screens in use; what an assistant is talking about. */
    SHOW,
    /** Nothing happened; keeps the line open through whatever sits in between. */
    KEEPALIVE,
}

/**
 * A verdict on one offer with the reason for it, written by the user or by an assistant reading the
 * ad for them, and shown wherever the offer is, so what was concluded in a conversation is on the
 * screen and not only in it.
 */
@Serializable
data class OfferNote(
    val listingId: String,
    val verdict: Verdict,
    val text: String,
    /** Who wrote it: "you", or the assistant that did. */
    val by: String = "you",
)

@Serializable
enum class Verdict(val label: String) { GOOD("Good"), ASK("Ask first"), CAREFUL("Careful"), AVOID("Avoid") }
