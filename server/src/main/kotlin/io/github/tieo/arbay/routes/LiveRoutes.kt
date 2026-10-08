package io.github.tieo.arbay.routes

import io.github.tieo.arbay.live.Live
import io.github.tieo.arbay.model.LiveEvent
import io.github.tieo.arbay.model.LiveKind
import io.ktor.http.ContentType
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.github.tieo.arbay.model.OnScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Changes as one JSON line each (see [Live]), for as long as the app holds the line. A keepalive
 * line every 20 seconds keeps proxies from closing a line that is quiet.
 */
fun Route.liveRoutes() {
    // Which page each app shows, so an assistant can know what the user is looking at.
    post("/api/live/here") {
        Live.here(call.receive<OnScreen>())
        call.respond(io.ktor.http.HttpStatusCode.NoContent)
    }

    get("/api/live") {
        call.respondTextWriter(ContentType.parse("application/x-ndjson")) {
            val lines = Channel<LiveEvent>(Channel.UNLIMITED)
            coroutineScope {
                val events = launch { Live.flow.collect { lines.send(it) } }
                val beat = launch { while (true) { lines.send(LiveEvent(LiveKind.KEEPALIVE)); delay(20_000) } }
                try {
                    for (e in lines) { write(Json.encodeToString(LiveEvent.serializer(), e) + "\n"); flush() }
                } finally {
                    events.cancel(); beat.cancel()
                }
            }
        }
    }
}
