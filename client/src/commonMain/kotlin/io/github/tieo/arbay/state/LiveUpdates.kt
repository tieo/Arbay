package io.github.tieo.arbay.state

import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.LiveEvent
import io.github.tieo.arbay.model.LiveKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Hold the server's line of changes open for as long as the app runs, and hand each one to
 * [onEvent]: a saved search renamed by an assistant, a look chosen on the phone, a reply that came
 * in. A dropped line is opened again after a short wait, and on reopening everything is reloaded
 * once, since what changed in between was not told.
 */
suspend fun followLive(client: ArbayClient, onEvent: (LiveEvent) -> Unit) {
    var first = true
    while (true) {
        try {
            if (!first) LiveKind.entries.filter { it != LiveKind.SHOW && it != LiveKind.KEEPALIVE }.forEach { onEvent(LiveEvent(it)) }
            first = false
            client.live().collect { if (it.kind != LiveKind.KEEPALIVE) onEvent(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        delay(5_000)
    }
}
