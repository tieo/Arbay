package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.CarTaxonomyProvider
import io.github.tieo.arbay.model.CarModelNode
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class MakeModels(val makeId: String, val models: List<CarModelNode>)

/** Serves the canonical car taxonomy. The app caches it and revalidates with the version
 *  in If-None-Match, so an unchanged taxonomy costs a 304 and no body. */
fun Route.taxonomyRoutes() {
    get("/api/car-taxonomy") {
        val taxonomy = CarTaxonomyProvider.current
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]?.trim('"')
        if (ifNoneMatch == taxonomy.version) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            call.response.headers.append(HttpHeaders.ETag, "\"${taxonomy.version}\"")
            call.respond(taxonomy)
        }
    }
    // Live model catalog for one make, probed from the site on first request and cached. The app
    // calls this when the picker opens a make, so models stay current without a bulk crawl.
    get("/api/car-taxonomy/models/{makeId}") {
        val makeId = call.parameters["makeId"]?.takeIf { it.isNotBlank() }
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "missing makeId"))
        call.respond(MakeModels(makeId, CarTaxonomyProvider.modelsFor(makeId)))
    }
    // Force a rebuild from the live site catalog (also runs on boot + daily).
    post("/api/car-taxonomy/refresh") {
        CarTaxonomyProvider.refresh()
        val t = CarTaxonomyProvider.current
        call.respond(mapOf("version" to t.version, "makes" to t.makes.size.toString()))
    }
}
