package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * What one market can do, as its crawler declares it.
 *
 * The app used to guess: it offered a market with no crawler at all, left the age blank on the
 * thirty-five markets that do not publish one without saying why, and showed a criterion applied by
 * the market itself exactly like one applied afterwards over whatever the first pages held. All of
 * it is known on the server; this is that knowledge, sent.
 */
@Serializable
data class MarketCapability(
    val platform: PlatformId,
    // False when the market is listed in the app and no crawler exists for it.
    val crawled: Boolean = true,
    val paginates: Boolean = false,
    // Criteria this market turns into its own URL parameters. Everything else is filtered after
    // the fetch, over the pages that happened to come back.
    val nativeCriteria: Set<String> = emptySet(),
    val soldListings: Boolean = false,
    val relatedSearches: Boolean = false,
    val listingAge: Boolean = false,
    val location: Boolean = false,
    val detailSpecs: Boolean = false,
    val currency: String? = null,
)
