package io.github.tieo.arbay.routes

import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import io.github.tieo.arbay.chat.Chat
import io.github.tieo.arbay.chat.ChatFailure
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
        // Kleinanzeigen where a request names no market, as the apps before other markets ask.
        get("/account") { call.respond(Chat.account(call.market())) }
        get("/accounts") { call.respond(Chat.accounts()) }
        // Whether Vinted's payment service still knows Arbay's browser, signed in again with the
        // saved password where it does not, as the daily check does.
        post("/payment/check") { call.chat { call.respond(mapOf("signedIn" to Chat.reloginPayment())) } }
        // A push to the user's phone that opens the app on the sign-in page.
        post("/signin/ask") {
            val link = io.github.tieo.arbay.model.SignInLink(call.market(), call.request.queryParameters["payment"] == "true")
            call.chat { call.respond(mapOf("sent" to Chat.askSignIn(link, force = true))) }
        }
        // Signing in from inside the app; what is typed is passed to the page and not kept.

        get("/conversations") { call.chat { call.respond(Chat.conversations()) } }
        // New messages from the other side, as the watch found them, after the one numbered
        // `after`; waits up to `waitSeconds` (at most a minute) for one when there is none yet.
        get("/incoming") {
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val wait = (call.request.queryParameters["waitSeconds"]?.toIntOrNull() ?: 0).coerceIn(0, 60)
            call.respond(io.github.tieo.arbay.chat.ChatWatch.after(after, wait.seconds))
        }
        // Everything from the other side not yet read, read now; nothing is marked read.
        get("/unread") { call.chat { call.respond(io.github.tieo.arbay.chat.ChatWatch.unreadNow()) } }
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

        // Buying a Vinted item outright: its checkout read, and paid only with pay and a total within allInEur.
        post("/buy") {
            val request = call.receive<io.github.tieo.arbay.model.PurchaseRequest>()
            call.chat { call.respond(Chat.buy(request)) }
        }

        // A price offer through "Sicher bezahlen": with only allInEur, the dialog is filled and read
        // and nothing is sent; with a price and send, that price is sent if the payment fits allInEur.
        post("/conversations/{id}/offer") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val body = call.receive<JsonObject>()
            val allIn = body["allInEur"]?.jsonPrimitive?.doubleOrNull ?: throw BadRequestException("allInEur")
            val price = body["price"]?.jsonPrimitive?.doubleOrNull
            val send = body["send"]?.jsonPrimitive?.booleanOrNull == true
            call.chat {
                if (send) call.respond(Chat.sendOffer(id, price ?: throw BadRequestException("price"), allIn))
                else call.respond(Chat.fitOffer(id, allIn))
            }
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

        get("/review") { call.chat { call.respond(Chat.review()) } }
        get("/settings") { call.respond(Chat.settings()) }
        get("/costs") { call.respond(io.github.tieo.arbay.judging.KleinanzeigenCosts.costs()) }
        put("/settings") { call.respond(Chat.updateSettings(call.receive<ChatSettings>())) }
    }
}

/** The market a request names in its `market` parameter, Kleinanzeigen where it names none. */
private fun ApplicationCall.market(): io.github.tieo.arbay.model.PlatformId =
    request.queryParameters["market"]?.let { name ->
        runCatching { io.github.tieo.arbay.model.PlatformId.valueOf(name) }.getOrNull() ?: throw BadRequestException("Unknown market $name")
    } ?: io.github.tieo.arbay.model.PlatformId.KLEINANZEIGEN

/** A signed-out account is the user's to fix (409); the browser failing is the server's (502). */
private suspend fun ApplicationCall.chat(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: ChatFailure) {
        respondText(
            if (e.signedOut) "Not signed in" else (e.message ?: "The market did not answer"),
            status = if (e.signedOut) HttpStatusCode.Conflict else HttpStatusCode.BadGateway,
        )
    }
}
