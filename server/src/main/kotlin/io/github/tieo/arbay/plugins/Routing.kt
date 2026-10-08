package io.github.tieo.arbay.plugins

import io.github.tieo.arbay.chat.Chat
import io.github.tieo.arbay.crawler.SavedSearchMonitor
import io.github.tieo.arbay.repo.ListingRepo
import io.github.tieo.arbay.repo.ProductRepo
import io.github.tieo.arbay.routes.archiveRoutes
import io.github.tieo.arbay.routes.crawlerRoutes
import io.github.tieo.arbay.routes.freeItemRoutes
import io.github.tieo.arbay.routes.auctionReminderRoutes
import io.github.tieo.arbay.routes.chatRoutes
import io.github.tieo.arbay.routes.userStateRoutes
import io.github.tieo.arbay.routes.mcpRoutes
import io.github.tieo.arbay.SERVER_PORT
import io.github.tieo.arbay.routes.importSettingsRoutes
import io.github.tieo.arbay.routes.listingRoutes
import io.github.tieo.arbay.routes.marketRoutes
import io.github.tieo.arbay.routes.placeRoutes
import io.github.tieo.arbay.routes.productRoutes
import io.github.tieo.arbay.routes.taxonomyRoutes
import io.github.tieo.arbay.routes.webAppRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val productRepo = ProductRepo()
    val listingRepo = ListingRepo()

    // Recurring saved-search updates. No-op for a search whose own autoFetch.enabled is false —
    // opted into per search, not turned on for every bookmark by one server-wide flag.
    val savedSearches = SavedSearchMonitor(productRepo, listingRepo)
    savedSearches.start()
    Chat.start()

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
        chatRoutes()
        userStateRoutes()
        mcpRoutes("http://127.0.0.1:$SERVER_PORT")
        placeRoutes()
        webAppRoutes()
    }
}
