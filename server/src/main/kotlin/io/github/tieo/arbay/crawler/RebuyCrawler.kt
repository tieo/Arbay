package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/** reBuy, read by Neuland in its render browser: its products in stock, used, delivered free. */
class RebuyCrawler : Crawler {
    override val platformId = PlatformId.REBUY

    override suspend fun search(query: SearchQuery): List<Listing> =
        NeulandOffers.search("rebuy.de", query.positiveText, platformId, "reBuy").map { it.copy(condition = Condition.USED) }
}
