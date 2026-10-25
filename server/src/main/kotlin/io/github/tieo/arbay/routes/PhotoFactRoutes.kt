package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.VanRoofFromPhotos
import io.github.tieo.arbay.plugins.BadRequestException
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the photos of offers are measured to tell. The models of a saved search are measured under
 *  /api/products/{id}/model-likeness. */
fun Route.photoFactRoutes() {
    route("/api/photo-facts") {
        // How often a van's roof is told from its photos alone, measured on the archived vans of the
        // model whose ad settles it. Embeds every photo not embedded before, so the first call takes minutes.
        get("/van-roof/{model}") {
            val model = call.parameters["model"]?.lowercase() ?: throw BadRequestException("Missing model")
            call.respond(withContext(Dispatchers.Default) { VanRoofFromPhotos.measure(model) })
        }
    }
}
