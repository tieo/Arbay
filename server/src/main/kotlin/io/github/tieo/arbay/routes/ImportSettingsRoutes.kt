package io.github.tieo.arbay.routes

import io.github.tieo.arbay.model.ImportSettings
import io.github.tieo.arbay.model.MarketSettings
import io.github.tieo.arbay.repo.ImportSettingsStore
import io.github.tieo.arbay.repo.MarketSettingsStore
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Where the buyer is and what import VAT they pay — read by the app, used here for subfilters. */
fun Route.importSettingsRoutes() {
    route("/api/settings/import") {
        get { call.respond(ImportSettingsStore.current) }
        post { call.respond(ImportSettingsStore.update(call.receive<ImportSettings>())) }
    }
    // Which countries a search covers when it names no markets itself.
    route("/api/settings/markets") {
        get { call.respond(MarketSettingsStore.current) }
        post { call.respond(MarketSettingsStore.update(call.receive<MarketSettings>())) }
    }
}
