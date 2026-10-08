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
