package io.github.tieo.arbay.routes

import io.github.tieo.arbay.chat.Chat
import io.github.tieo.arbay.chat.ChatBrowser
import io.github.tieo.arbay.model.ChatSettings
import io.github.tieo.arbay.model.SendRequest
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.plugins.NotFoundException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class ReplyBody(val text: String)

/** The user's conversations with sellers, through their own Kleinanzeigen account. */
fun Route.chatRoutes() {
    route("/api/chat") {
        get("/account") { call.respond(Chat.account()) }
        post("/account/signin") { call.respond(Chat.beginSignIn()) }
        post("/account/signin/done") { call.respond(Chat.endSignIn()) }

        get("/conversations") { call.chat { call.respond(Chat.conversations()) } }
        get("/conversations/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            call.chat { call.respond(Chat.conversation(id) ?: throw NotFoundException("No conversation $id")) }
        }
        post("/conversations/{id}/read") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            call.chat { Chat.markRead(id); call.respond(HttpStatusCode.OK, mapOf("ok" to true)) }
        }
        post("/conversations/{id}/reply") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val text = call.receive<ReplyBody>().text.trim()
            if (text.isEmpty()) throw BadRequestException("Nothing to send")
            call.chat { Chat.reply(id, text); call.respond(HttpStatusCode.OK, mapOf("ok" to true)) }
        }

        get("/outbox") { call.respond(Chat.outbox()) }
        post("/outbox") {
            val request = call.receive<SendRequest>()
            if (request.messages.isEmpty() || request.messages.any { it.text.isBlank() }) throw BadRequestException("Nothing to send")
            call.respond(Chat.queue(request))
        }
        delete("/outbox/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            call.respond(HttpStatusCode.OK, mapOf("cancelled" to Chat.cancel(id)))
        }

        get("/settings") { call.respond(Chat.settings()) }
        put("/settings") { call.respond(Chat.updateSettings(call.receive<ChatSettings>())) }
    }
}

/** A signed-out account is the user's to fix (409); the browser failing is the server's (502). */
private suspend fun ApplicationCall.chat(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: ChatBrowser.ChatFailure) {
        respondText(
            if (e.signedOut) "Not signed in to Kleinanzeigen" else (e.message ?: "Kleinanzeigen did not answer"),
            status = if (e.signedOut) HttpStatusCode.Conflict else HttpStatusCode.BadGateway,
        )
    }
}
