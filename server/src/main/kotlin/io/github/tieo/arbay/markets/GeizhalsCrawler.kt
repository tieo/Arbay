package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/** Geizhals, read by Neuland: its search sorted by price, each product with its lowest item price. */
class GeizhalsCrawler : Crawler {
    override val platformId = PlatformId.GEIZHALS

    override suspend fun search(query: SearchQuery): List<Listing> =
        NeulandOffers.search("geizhals.de", query.positiveText, platformId, "Geizhals")
}
