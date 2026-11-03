package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/** Back Market, read by Neuland in its render browser: refurbished products, delivered free. */
class BackMarketCrawler : Crawler {
    override val platformId = PlatformId.BACKMARKET_DE

    override suspend fun search(query: SearchQuery): List<Listing> =
        NeulandOffers.search("backmarket.de", query.positiveText, platformId, "Back Market").map { it.copy(condition = Condition.REFURBISHED) }
}
