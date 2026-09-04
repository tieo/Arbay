package io.github.tieo.arbay.routes

import io.github.tieo.arbay.plugins.NotFoundException
import io.github.tieo.arbay.repo.ListingArchive
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Serves what [ListingArchive] has saved of a listing that may no longer exist on its own
 *  platform: the listing's own fields, and the images mirrored alongside it. */
fun Route.archiveRoutes() {
    route("/api/archive") {
        get("/listings/{id}") {
            val id = call.parameters["id"] ?: throw NotFoundException("Missing id")
            val listing = ListingArchive.get(id) ?: throw NotFoundException("No archived copy of $id")
            call.respond(listing)
        }
        get("/images/{id}/{filename}") {
            val id = call.parameters["id"] ?: throw NotFoundException("Missing id")
            val filename = call.parameters["filename"] ?: throw NotFoundException("Missing filename")
            val file = ListingArchive.imageFile(id, filename) ?: throw NotFoundException("No image $filename for $id")
            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=604800, immutable")
            call.respondFile(file)
        }
    }
}
