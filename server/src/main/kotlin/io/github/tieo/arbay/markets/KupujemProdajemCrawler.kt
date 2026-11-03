package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.relevance.CarQueryResolver

/**
 * kupujemprodajem.com, Serbia's largest classifieds market, its car section, read by Neuland: by
 * the make and model the words name (or the words), the cars among what its word search answers.
 * Used because polovniautomobili.com ignores the model filter and hides its results behind a
 * client-only API. Its search takes no car filters, so they are applied here.
 */
class KupujemProdajemCrawler : Crawler, StartsAtAnyPage {
    override val platformId = PlatformId.KUPUJEM

    override suspend fun search(query: SearchQuery): List<Listing> {
        val car = CarQueryResolver.resolveForCarSite(query.positiveText)
        val vehicle = NeulandOffers.VehicleQuery(make = car?.makeSlug, model = car?.modelSlug)
        return paginate(query) { page ->
            NeulandOffers.searchVehicles("kupujemprodajem.com", query.positiveText, platformId, "KupujemProdajem", vehicle, page, query.minPrice, query.maxPrice)
        }
    }
}
