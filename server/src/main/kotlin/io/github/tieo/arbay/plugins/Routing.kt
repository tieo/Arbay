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
import io.ktor.http.CacheControl
import io.ktor.server.http.content.CompressedFileType
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
        // path is one of its files, and the app itself is index.html. A wasm module is named by
        // its content, so a browser may keep it for good; everything else is asked about again,
        // since index.html and the script chunks keep their names from one build to the next.
        staticResources("/", "web", index = "index.html") {
            preCompressed(CompressedFileType.GZIP)
            cacheControl { url ->
                if (url.path.substringAfterLast('/').contains(".wasm")) listOf(CacheControl.MaxAge(maxAgeSeconds = 31_536_000, visibility = CacheControl.Visibility.Public), immutable)
                else listOf(CacheControl.NoCache(null))
            }
        }
    }
}

private val immutable = object : CacheControl(null) {
    override fun toString() = "immutable"
}
