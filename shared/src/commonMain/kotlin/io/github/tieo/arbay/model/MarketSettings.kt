package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * Which countries a search covers unless it says otherwise.
 *
 * Every search reaches a set of markets, and which countries those markets sell in is the single
 * biggest thing about a search: an offer two countries away is a different proposition from one
 * down the road, and a search that quietly spans a continent explains neither its own runtime nor
 * half of what it found. So it is a setting with a default, shown on the search itself, rather than
 * a list of markets someone has to reason about market by market.
 */
@Serializable
data class MarketSettings(
    /** ISO 3166-1 alpha-2, in the order they were picked. Empty means every country the app can
     *  reach, which is what someone has to ask for rather than land in. */
    val countries: List<String> = listOf("DE"),
)

/** The markets in these countries, for a kind of search. An empty [countries] reaches all of them.
 *  A country nobody has a crawler for simply contributes nothing, which is why the result is
 *  checked by the caller rather than assumed non-empty. */
fun MarketSets.platformsIn(group: MarketGroup, countries: List<String>): List<PlatformId> {
    val wanted = countries.map { it.uppercase() }.toSet()
    val all = platformsFor(group)
    if (wanted.isEmpty()) return all
    return all.filter { countryOf(it).uppercase() in wanted }
}
