package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.Geocoder
import io.github.tieo.arbay.model.ImportSettings
import io.github.tieo.arbay.model.MarketSettings
import io.github.tieo.arbay.repo.ImportSettingsStore
import io.github.tieo.arbay.repo.MarketSettingsStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Where the buyer is and what import VAT they pay — read by the app, used here for subfilters. */
fun Route.importSettingsRoutes() {
    route("/api/settings/import") {
        get { call.respond(ImportSettingsStore.current) }
        post { call.respond(ImportSettingsStore.update(call.receive<ImportSettings>())) }
    }
    /** Where a place is, so the app can measure a listing against a home town when it has no
     *  position of its own to measure from. The same index the server geocodes listings with. */
    get("/api/geocode") {
        val place = call.request.queryParameters["q"].orEmpty()
        val country = call.request.queryParameters["country"]
        val coords = Geocoder.resolve(country, place.takeIf { it.any(Char::isDigit) }, place)
        if (coords == null) call.respond(HttpStatusCode.NotFound, mapOf("found" to false))
        else call.respond(mapOf("latitude" to coords.first, "longitude" to coords.second))
    }

    // Which countries a search covers when it names no markets itself.
    route("/api/settings/markets") {
        get { call.respond(MarketSettingsStore.current) }
        post { call.respond(MarketSettingsStore.update(call.receive<MarketSettings>())) }
    }
}
