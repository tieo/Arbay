package io.github.tieo.arbay.routes

import io.github.tieo.arbay.chat.Chat
import io.github.tieo.arbay.chat.ChatBrowser
import io.github.tieo.arbay.model.ChatSettings
import io.github.tieo.arbay.model.SendRequest
import io.github.tieo.arbay.model.SignInInput
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.plugins.NotFoundException
import io.github.tieo.arbay.signin.SignInProxy
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
        // Signing in from inside the app; what is typed is passed to the page and not kept.
        post("/signin") { call.chat { call.respond(Chat.beginSignIn()) } }
        get("/signin") { call.chat { call.respond(Chat.signInStep()) } }
        post("/signin/input") { call.chat { call.respond(Chat.signInWith(call.receive<SignInInput>())) } }
        post("/signin/cancel") { call.respond(Chat.cancelSignIn()) }
        // Signing in on the site's own pages, shown inside the app through the proxy. The session cookie
        // is only sent to the proxy's paths, so the browser holds an id and nothing of the site's.
        post("/signin/proxy") {
            val id = Chat.beginProxySignIn()
            call.response.cookies.append(Cookie(
                name = SignInProxy.COOKIE_NAME,
                value = id,
                encoding = CookieEncoding.RAW,
                path = SignInProxy.COOKIE_PATH,
                // Behind the TLS terminator the request arrives as plain http; the terminator says what the browser used.
                secure = call.request.headers["X-Forwarded-Proto"].equals("https", ignoreCase = true),
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            ))
            call.respond(Chat.proxyAccount())
        }

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

        get("/review") { call.chat { call.respond(Chat.review()) } }
        get("/settings") { call.respond(Chat.settings()) }
        // The message dialog of one ad, opened and looked at; nothing is typed or sent.
        get("/contact-preview") {
            val adId = call.queryParameters["adId"]?.takeIf { it.all(Char::isDigit) } ?: throw BadRequestException("adId")
            call.chat { call.respond(Chat.contactPreview(adId)) }
        }
        get("/costs") { call.respond(io.github.tieo.arbay.crawler.KleinanzeigenCosts.costs()) }
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
