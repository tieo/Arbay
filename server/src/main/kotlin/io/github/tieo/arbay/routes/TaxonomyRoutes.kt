package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.CarTaxonomyProvider
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Serves the canonical car taxonomy — every make and its full model list already baked in,
 *  refreshed on its own schedule by [CarTaxonomyProvider]. The app caches it and revalidates
 *  with the version in If-None-Match, so an unchanged taxonomy costs a 304 and no body. */
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
    // Force a rebuild from the live site catalog (also runs on boot + daily).
    post("/api/car-taxonomy/refresh") {
        CarTaxonomyProvider.refresh()
        val t = CarTaxonomyProvider.current
        call.respond(mapOf("version" to t.version, "makes" to t.makes.size.toString()))
    }
}
