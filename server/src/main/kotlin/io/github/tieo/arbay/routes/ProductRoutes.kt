package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.SavedSearchMonitor
import io.github.tieo.arbay.live.Live
import io.github.tieo.arbay.model.LiveKind
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.plugins.NotFoundException
import io.github.tieo.arbay.repo.ProductRepo
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.productRoutes(repo: ProductRepo, savedSearches: SavedSearchMonitor) {
    route("/api/products") {
        get {
            call.respond(repo.getAll())
        }

        // What each saved search has found since it was last opened, and whether it is watched at
        // all. Separate from the products themselves because it changes without them changing.
        get("/status") {
            call.respond(savedSearches.statuses())
        }

        // What this saved search turned up since it was last looked at, as it was when the watch
        // found it. Answered from what was stored at crawl time, so opening it costs no crawl and
        // still shows listings the platform has since taken down.
        get("/{id}/new") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            call.respond(savedSearches.newListings(id))
        }

        // Every offer a saved search's alert let through, after the one numbered `after`; waits up
        // to `waitSeconds` (at most a minute) for one when there is none yet.
        get("/alerts") {
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val wait = (call.request.queryParameters["waitSeconds"]?.toIntOrNull() ?: 0).coerceIn(0, 60)
            call.respond(io.github.tieo.arbay.crawler.AlertFeed.after(after, kotlin.time.Duration.parse("${wait}s")))
        }
        // The saved search was opened, so what was waiting in it has been seen.
        post("/{id}/opened") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            savedSearches.markOpened(id)
            call.respond(HttpStatusCode.NoContent)
        }

        // How often this search's offers are told their model from their photos alone, measured on
        // the archived offers whose title names it. Embeds every photo not embedded before, so the
        // first call takes minutes.
        get("/{id}/model-likeness") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val product = repo.getById(id) ?: throw NotFoundException("Product $id not found")
            val wishes = product.searchQuery.productWishes?.takeUnless { it.isEmpty }
                ?: throw BadRequestException("Product $id weighs no models")
            call.respond(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                io.github.tieo.arbay.crawler.ModelLikeness.measure(product.searchQuery, wishes)
            })
        }

        // A reviewer's verdict on which of this search's models an offer is, or none of them.
        put("/{id}/model-checks/{listingId}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val listingId = call.parameters["listingId"] ?: throw BadRequestException("Missing listing id")
            val product = repo.getById(id) ?: throw NotFoundException("Product $id not found")
            val model = call.receive<io.github.tieo.arbay.model.ModelCheck>().model
            val names = product.searchQuery.productWishes?.models.orEmpty().map { it.name }
            if (model != null && model !in names) throw BadRequestException("$model is none of ${names.joinToString()}")
            io.github.tieo.arbay.crawler.ModelGuesses.check(listingId, model)
            call.respond(HttpStatusCode.NoContent)
        }

        get("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val product = repo.getById(id) ?: throw NotFoundException("Product $id not found")
            call.respond(product)
        }

        post {
            val product = call.receive<TrackedProduct>()
            call.respond(HttpStatusCode.Created, repo.create(product)).also { Live.changed(LiveKind.SAVED_SEARCHES) }
        }

        put("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            val product = call.receive<TrackedProduct>()
            if (product.id != id) throw BadRequestException("ID mismatch")
            val updated = repo.update(product) ?: throw NotFoundException("Product $id not found")
            Live.changed(LiveKind.SAVED_SEARCHES)
            call.respond(updated)
        }

        delete("/{id}") {
            val id = call.parameters["id"] ?: throw BadRequestException("Missing id")
            if (!repo.delete(id)) throw NotFoundException("Product $id not found")
            Live.changed(LiveKind.SAVED_SEARCHES)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
