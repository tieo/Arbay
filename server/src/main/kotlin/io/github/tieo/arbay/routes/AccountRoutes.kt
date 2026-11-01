package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.Neuland
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Signing Neuland's account browser in to the user's accounts (Google, PayPal, shops) from the app.
 * The app seals the password and any code to Neuland's key; these routes pass the sealed text on
 * and never hold anything they could open. Nothing of a request body is logged.
 */
fun Route.accountRoutes() {
    route("/api/accounts") {
        // Neuland's public key and where each site's sign-in stands.
        get { call.passOn(HttpMethod.Get, "accounts", null) }
        // {"account", "password"}, the password sealed: starts signing in.
        post("/{site}/sign-in") { call.passOn(HttpMethod.Post, "accounts/${call.site()}/sign-in", call.receiveText()) }
        // {"code"}, sealed: the code the site asked for.
        post("/{site}/code") { call.passOn(HttpMethod.Post, "accounts/${call.site()}/code", call.receiveText()) }
        // Stops a sign-in waiting for the phone or a code.
        post("/{site}/cancel") { call.passOn(HttpMethod.Post, "accounts/${call.site()}/cancel", null) }
    }
}

/** The site's name as the path gives it, and nothing that could leave Neuland's accounts path. */
private fun RoutingCall.site(): String =
    parameters["site"].orEmpty().filter { it.isLetterOrDigit() || it == '.' || it == '-' }

private suspend fun RoutingCall.passOn(method: HttpMethod, path: String, body: String?) {
    val (status, answer) = Neuland.passOn(method, path, body)
        ?: return respondText("""{"error":"Neuland is not reachable"}""", ContentType.Application.Json, HttpStatusCode.BadGateway)
    respondText(answer, ContentType.Application.Json, HttpStatusCode.fromValue(status))
}
