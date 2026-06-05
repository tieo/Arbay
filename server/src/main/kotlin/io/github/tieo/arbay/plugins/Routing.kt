package io.github.tieo.arbay.plugins

import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.ProductRepo
import io.github.tieo.arbay.routes.crawlerRoutes
import io.github.tieo.arbay.routes.freeItemRoutes
import io.github.tieo.arbay.routes.listingRoutes
import io.github.tieo.arbay.routes.productRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val productRepo = ProductRepo()
    val listingRepo = ListingRepo()

    routing {
        productRoutes(productRepo)
        listingRoutes(listingRepo)
        crawlerRoutes(listingRepo)
        freeItemRoutes()
    }
}
