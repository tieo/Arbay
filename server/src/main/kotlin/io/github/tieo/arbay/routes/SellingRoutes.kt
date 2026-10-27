package io.github.tieo.arbay.routes

import io.github.tieo.arbay.model.AdDraft
import io.github.tieo.arbay.model.NewAdDraft
import io.github.tieo.arbay.model.RepostEvery
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

/** The user's ads for sale on Kleinanzeigen: drafts, their photos, posting them and changing them once posted. */
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
        // One photo of the user's own as the request body, added as the draft's last.
        post("/{id}/photos") { call.selling { call.respond(Selling.addPhoto(call.id(), call.receive<ByteArray>())) } }
        get("/{id}/photos/{photo}") {
            val file = Selling.photoFile(call.id(), call.parameters["photo"].orEmpty()) ?: throw NotFoundException("No such photo")
            call.respondFile(file)
        }
        // A posted ad changed on the market and kept as changed; with ?try=true the edit form is
        // filled and left without saving, and the answer is what it took, with a picture of it.
        post("/{id}/update") {
            val draft = call.receive<AdDraft>()
            if (draft.id != call.id()) throw BadRequestException("The draft's id is ${draft.id}")
            val send = call.request.queryParameters["try"] != "true"
            call.selling {
                val (kept, answer) = Selling.update(draft, send)
                if (send) call.respond(kept) else call.respond(answer ?: JsonObject(emptyMap()))
            }
        }
        post("/{id}/post") { call.selling { call.respond(Selling.post(call.id()).first) } }
        // Putting the posted ad up again gone through without posting or taking anything down.
        post("/{id}/try-repost") { call.selling { call.respond(Selling.tryRepost(call.id())) } }
        // How often the posted ad goes up again: {"everyDays": 7}, or null to leave it as posted.
        put("/{id}/repost") {
            val days = call.receive<RepostEvery>().everyDays
            call.selling { call.respond(Selling.setRepost(call.id(), days)) }
        }
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
