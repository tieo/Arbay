package io.github.tieo.arbay.routes

import io.github.tieo.arbay.neuland.Neuland
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Signing Neuland's account browser in to the user's accounts (Google, PayPal, shops): Auth
 * holds each sign-in and its page, where the password is given and sealed, so no secret passes
 * through Arbay.
 */
fun Route.accountRoutes() {
    route("/api/accounts") {
        // Every site, where the account browser stands with each, and each latest sign-in's page.
        get { call.passOn(HttpMethod.Get, "accounts", null) }
        // A sign-in through Auth, answering its id and page. Neuland pushes the page to the
        // user's phone unless `push=false` (the app opens it itself). The account is the one asked,
        // or the one the site was last signed in to with; `asker` says who wants it.
        post("/{site}/ask") {
            val site = call.site()
            val account = call.request.queryParameters["account"]
                ?: Neuland.passOn(HttpMethod.Get, "accounts", null)?.second?.let { known ->
                    runCatching { Json.parseToJsonElement(known) }.getOrNull()
                        ?.let { (it as? JsonObject)?.get("accounts") as? JsonObject }
                        ?.get(site)?.let { (it as? JsonPrimitive)?.content }
                }
                ?: return@post call.respondText("""{"error":"name the account to sign in"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
            val body = buildJsonObject {
                put("site", JsonPrimitive(site))
                put("account", JsonPrimitive(account))
                call.request.queryParameters["asker"]?.let { put("asker", JsonPrimitive(it)) }
                call.request.queryParameters["push"]?.toBooleanStrictOrNull()?.let { put("push", JsonPrimitive(it)) }
            }
            call.passOn(HttpMethod.Post, "sign-ins", body.toString())
        }
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
