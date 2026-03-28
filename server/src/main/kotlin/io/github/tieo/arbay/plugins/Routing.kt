package io.github.tieo.arbay.plugins

import io.github.tieo.arbay.crawler.CrawlerScheduler
import io.github.tieo.arbay.repo.AlertRepo
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.ProductRepo
import io.github.tieo.arbay.routes.alertRoutes
import io.github.tieo.arbay.routes.crawlerRoutes
import io.github.tieo.arbay.routes.listingRoutes
import io.github.tieo.arbay.routes.productRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val productRepo = ProductRepo()
    val listingRepo = ListingRepo()
    val alertRepo = AlertRepo()

    routing {
        productRoutes(productRepo)
        listingRoutes(listingRepo)
        alertRoutes(alertRepo)
        crawlerRoutes(listingRepo)
    }

    val scheduler = CrawlerScheduler(productRepo, listingRepo, alertRepo)
    scheduler.start()

    monitor.subscribe(ApplicationStopped) {
        scheduler.stop()
    }
}
