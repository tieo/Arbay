package io.github.tieo.arbay.plugins

import io.github.tieo.arbay.crawler.SavedSearchMonitor
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.ProductRepo
import io.github.tieo.arbay.routes.crawlerRoutes
import io.github.tieo.arbay.routes.freeItemRoutes
import io.github.tieo.arbay.routes.listingRoutes
import io.github.tieo.arbay.routes.marketRoutes
import io.github.tieo.arbay.routes.productRoutes
import io.github.tieo.arbay.routes.taxonomyRoutes
import io.ktor.server.application.*
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
    }
}
