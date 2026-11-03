package io.github.tieo.arbay.chat

import kotlinx.serialization.json.JsonObject

/** A request a market's account answered as failed; [detail] is what Neuland answered with, when it sent more. */
class ChatFailure(message: String, val signedOut: Boolean, val detail: JsonObject? = null) : RuntimeException(message)
