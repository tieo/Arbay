package io.github.tieo.arbay.routes

import io.github.tieo.arbay.live.Live
import io.github.tieo.arbay.model.LiveKind
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.repo.UserStateStore
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonElement

/** The user's state that every device shares; see [UserStateStore]. */
fun Route.userStateRoutes() {
    route("/api/state") {
        get { call.respond(UserStateStore.all()) }
        put("/{key}") {
            val key = call.parameters["key"]?.takeIf { it in UserStateStore.KEYS } ?: throw BadRequestException("No such state")
            UserStateStore.put(key, call.receive<JsonElement>())
            Live.changed(LiveKind.STATE)
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}
