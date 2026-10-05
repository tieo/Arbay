package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.Geocoder
import io.github.tieo.arbay.plugins.NotFoundException
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The name of the place a point is in, for a device that knows where it is but has no way of
 *  naming it: a browser reads coordinates, and only the phone has a geocoder of its own. */
fun Route.placeRoutes() {
    get("/api/place") {
        val lat = call.request.queryParameters["lat"]?.toDoubleOrNull()
        val lon = call.request.queryParameters["lon"]?.toDoubleOrNull()
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            call.respondText("lat and lon are needed", status = HttpStatusCode.BadRequest)
            return@get
        }
        val place = withContext(Dispatchers.Default) { Geocoder.nearestPlace(lat, lon) }
            ?: throw NotFoundException("No place known near $lat, $lon")
        call.respondText(place.name)
    }
}
