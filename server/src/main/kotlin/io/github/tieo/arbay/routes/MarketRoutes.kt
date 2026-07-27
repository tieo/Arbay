package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.MarketCapabilities
import io.github.tieo.arbay.model.MarketCapability
import io.github.tieo.arbay.model.PlatformId
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** What each market can do, read from the crawlers themselves so the app never has to hold a copy
 *  of it that can fall out of step. */
fun Route.marketRoutes() {
    get("/api/markets") {
        call.respond(
            PlatformId.entries.map { platform ->
                val crawler = CrawlerRegistry.crawlerFor(platform)
                    ?: return@map MarketCapability(platform = platform, crawled = false)
                val can = MarketCapabilities.of(crawler)
                MarketCapability(
                    platform = platform,
                    crawled = true,
                    paginates = can.paginates,
                    nativeCriteria = can.nativeCriteria.map { it.name }.toSet(),
                    soldListings = can.soldListings,
                    relatedSearches = can.relatedSearches,
                    listingAge = can.listingAge,
                    location = can.location,
                    detailSpecs = can.detailSpecs,
                    currency = can.currency?.name,
                )
            },
        )
    }
}
