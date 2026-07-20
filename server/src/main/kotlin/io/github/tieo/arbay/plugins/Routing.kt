package io.github.tieo.arbay.plugins

import io.github.tieo.arbay.crawler.SavedSearchMonitor
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.ProductRepo
import io.github.tieo.arbay.routes.crawlerRoutes
import io.github.tieo.arbay.routes.freeItemRoutes
import io.github.tieo.arbay.routes.listingRoutes
import io.github.tieo.arbay.routes.productRoutes
import io.github.tieo.arbay.routes.taxonomyRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val productRepo = ProductRepo()
    val listingRepo = ListingRepo()

    // Recurring saved-search updates. No-op unless ARBAY_SAVED_SEARCH_UPDATES=on, since periodic
    // crawls raise the flag risk the anti-block work manages.
    SavedSearchMonitor(productRepo).start()

    routing {
        productRoutes(productRepo)
        listingRoutes(listingRepo)
        crawlerRoutes(listingRepo)
        freeItemRoutes()
        taxonomyRoutes()
    }
}
