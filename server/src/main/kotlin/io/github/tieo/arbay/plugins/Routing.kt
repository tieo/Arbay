package io.github.tieo.arbay.plugins

import io.github.tieo.arbay.crawler.SavedSearchMonitor
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.ProductRepo
import io.github.tieo.arbay.routes.archiveRoutes
import io.github.tieo.arbay.routes.crawlerRoutes
import io.github.tieo.arbay.routes.freeItemRoutes
import io.github.tieo.arbay.routes.auctionReminderRoutes
import io.github.tieo.arbay.routes.imageProxyRoutes
import io.github.tieo.arbay.routes.importSettingsRoutes
import io.github.tieo.arbay.routes.listingRoutes
import io.github.tieo.arbay.routes.marketRoutes
import io.github.tieo.arbay.routes.placeRoutes
import io.github.tieo.arbay.routes.productRoutes
import io.github.tieo.arbay.routes.taxonomyRoutes
import io.ktor.server.application.*
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val productRepo = ProductRepo()
    val listingRepo = ListingRepo()

    // Recurring saved-search updates. No-op for a search whose own autoFetch.enabled is false —
    // opted into per search, not turned on for every bookmark by one server-wide flag.
    val savedSearches = SavedSearchMonitor(productRepo, listingRepo)
    savedSearches.start()

    routing {
        productRoutes(productRepo, savedSearches)
        marketRoutes()
        listingRoutes(listingRepo)
        crawlerRoutes(listingRepo)
        freeItemRoutes(savedSearches)
        taxonomyRoutes()
        archiveRoutes()
        importSettingsRoutes()
        auctionReminderRoutes()
        imageProxyRoutes()
        placeRoutes()
        // The web app, when this server was built with it (see the shadowJar task): every other
        // path is one of its files, and the app itself is index.html.
        staticResources("/", "web", index = "index.html")
    }
}
