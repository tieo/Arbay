package io.github.tieo.arbay.routes

import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.plugins.NotFoundException
import io.github.tieo.arbay.repo.AlertRepo
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.alertRoutes(repo: AlertRepo) {
    route("/api/alerts") {
        get {
            val unreadOnly = call.queryParameters["unread"]?.toBooleanStrictOrNull() ?: false
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respond(repo.getAll(unreadOnly, limit))
        }

        get("/product/{productId}") {
            val productId = call.parameters["productId"] ?: throw BadRequestException("Missing productId")
            call.respond(repo.getByProductId(productId))
        }

        post("/{id}/read") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val alert = repo.markRead(id) ?: throw NotFoundException("Alert $id not found")
            call.respond(alert)
        }

        post("/read-all") {
            val count = repo.markAllRead()
            call.respond(mapOf("marked" to count))
        }
    }
}
