package io.github.tieo.arbay.routes

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.plugins.NotFoundException
import io.github.tieo.arbay.repo.ListingRepo
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.listingRoutes(repo: ListingRepo) {
    route("/api/listings") {
        get {
            val platform = call.queryParameters["platform"]?.let {
                runCatching { PlatformId.valueOf(it) }.getOrNull()
            }
            val sold = call.queryParameters["sold"]?.toBooleanStrictOrNull()
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 50
            val offset = call.queryParameters["offset"]?.toIntOrNull() ?: 0
            call.respond(repo.getAll(platform, sold, limit, offset))
        }

        get("/search") {
            val query = call.queryParameters["q"] ?: throw BadRequestException("Missing query parameter 'q'")
            val limit = call.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respond(repo.search(query, limit))
        }

        get("/price-history") {
            val query = call.queryParameters["q"] ?: throw BadRequestException("Missing query parameter 'q'")
            val platform = call.queryParameters["platform"]?.let {
                runCatching { PlatformId.valueOf(it) }.getOrNull()
            }
            call.respond(repo.getPriceHistory(platform, query))
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val listing = repo.getById(id) ?: throw NotFoundException("Listing $id not found")
            call.respond(listing)
        }

        post("/batch") {
            val listings = call.receive<List<Listing>>()
            val count = repo.upsertBatch(listings)
            call.respond(mapOf("upserted" to count))
        }
    }
}
