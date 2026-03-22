package io.github.tieo.arbay.routes

import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.plugins.NotFoundException
import io.github.tieo.arbay.repo.ProductRepo
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.productRoutes(repo: ProductRepo) {
    route("/api/products") {
        get {
            call.respond(repo.getAll())
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val product = repo.getById(id) ?: throw NotFoundException("Product $id not found")
            call.respond(product)
        }

        post {
            val product = call.receive<TrackedProduct>()
            call.respond(HttpStatusCode.Created, repo.create(product))
        }

        put("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val product = call.receive<TrackedProduct>()
            if (product.id != id) throw BadRequestException("ID mismatch")
            val updated = repo.update(product) ?: throw NotFoundException("Product $id not found")
            call.respond(updated)
        }

        delete("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            if (!repo.delete(id)) throw NotFoundException("Product $id not found")
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
