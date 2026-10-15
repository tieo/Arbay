package io.github.tieo.arbay.routes

import io.github.tieo.arbay.model.AdDraft
import io.github.tieo.arbay.model.NewAdDraft
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.plugins.NotFoundException
import io.github.tieo.arbay.selling.Selling
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The user's ads for sale on Kleinanzeigen: drafts, their photos, and posting them. */
fun Route.sellingRoutes() {
    route("/api/selling/drafts") {
        get { call.respond(Selling.drafts()) }
        post { call.selling { call.respond(Selling.fromListing(call.receive<NewAdDraft>().listingId)) } }
        get("/{id}") { call.respond(Selling.draft(call.id()) ?: throw NotFoundException("No draft ${call.id()}")) }
        put("/{id}") {
            val draft = call.receive<AdDraft>()
            if (draft.id != call.id()) throw BadRequestException("The draft's id is ${draft.id}")
            call.selling { call.respond(Selling.change(draft)) }
        }
        delete("/{id}") { Selling.delete(call.id()); call.respond(HttpStatusCode.NoContent) }
        get("/{id}/photos/{photo}") {
            val file = Selling.photoFile(call.id(), call.parameters["photo"].orEmpty()) ?: throw NotFoundException("No such photo")
            call.respondFile(file)
        }
        post("/{id}/post") { call.selling { call.respond(Selling.post(call.id()).first) } }
        // Fills the market's post form and leaves it without posting: what the form took, with a
        // picture of it, for checking the steps against the market as it is now.
        post("/{id}/try") {
            call.selling {
                val (result, answer) = Selling.post(call.id(), send = false)
                call.respond(JsonObject((answer ?: JsonObject(emptyMap())) + ("error" to JsonPrimitive(result.error))))
            }
        }
    }
}

private fun ApplicationCall.id(): String = parameters["id"] ?: throw BadRequestException("Missing id")

/** A draft that cannot be made or changed is the user's to fix (422). */
private suspend fun ApplicationCall.selling(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: Selling.SellingFailure) {
        respondText(e.message ?: "Failed", status = HttpStatusCode.UnprocessableEntity)
    }
}
