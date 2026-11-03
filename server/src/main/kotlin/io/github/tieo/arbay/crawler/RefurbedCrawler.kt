package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/**
 * Refurbed, read by Neuland: the products its own autosuggest names for the words, each at the
 * lowest price its page states, delivered free.
 */
class RefurbedCrawler : Crawler {
    override val platformId = PlatformId.REFURBED

    override suspend fun search(query: SearchQuery): List<Listing> =
        NeulandOffers.search("refurbed.de", query.positiveText, platformId, "Refurbed", otherwise = Condition.REFURBISHED)
            .map { it.copy(condition = Condition.REFURBISHED) }
}
