package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.NeulandOffers

/** ImmobilienScout24's flats to rent around the search's place, inside its rent band, read by Neuland. */
class ImmoScout24Crawler : Crawler {
    override val platformId = PlatformId.IMMOSCOUT24

    override suspend fun search(query: SearchQuery): List<Listing> =
        NeulandOffers.ask(
            "immobilienscout24.de", query.positiveText, platformId, "ImmobilienScout24", query.startPage,
            query.minPrice, query.maxPrice, near = query.area("DE"),
        ).listings
}
