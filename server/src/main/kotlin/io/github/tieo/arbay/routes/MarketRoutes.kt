package io.github.tieo.arbay.routes

import io.github.tieo.arbay.markets.CrawlerRegistry
import io.github.tieo.arbay.markets.MarketCapabilities
import io.github.tieo.arbay.model.MarketCapability
import io.github.tieo.arbay.model.PlatformId
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** What each market can do, read from the crawlers themselves so the app never has to hold a copy
 *  of it that can fall out of step. */
fun Route.marketRoutes() {
    // What a model, a size or some words sold for on eBay.de lately. One of model, capacityGb, words.
    get("/api/market/sold") {
        val p = call.request.queryParameters
        val ask = io.github.tieo.arbay.judging.SoldPriceReader.Ask(
            model = p["model"]?.takeIf { it.isNotBlank() }?.uppercase(),
            capacityGb = p["capacityGb"]?.toIntOrNull()?.takeIf { it > 0 },
            words = p["words"]?.takeIf { it.isNotBlank() },
        )
        if (ask.model == null && ask.capacityGb == null && ask.words == null) throw io.github.tieo.arbay.plugins.BadRequestException("name a model, a size or words")
        call.respond(io.github.tieo.arbay.judging.SoldPriceReader.of(ask))
    }

    // Reads Backblaze's drive records again now; the running table is replaced only by a usable one.
    post("/api/market/drive-records/refresh") {
        call.respond(mapOf("result" to io.github.tieo.arbay.judging.DriveLife.refresh()))
    }

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
