package io.github.tieo.arbay.results

import io.github.tieo.arbay.SearchCountries
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.platformsIn

/** Every market of a search's kind, whatever a crawl asked and whatever is ticked. The only thing
 *  that narrows this list is the kind of search: a vehicle search has no business offering Vinted. */
fun coveredMarkets(isCar: Boolean): List<PlatformId> =
    MarketSets.platformsFor(if (isCar) MarketGroup.VEHICLES else MarketGroup.GENERAL)

/**
 * The markets a search actually asks: the ones it carries, else every one of its kind in the
 * countries searched, plus anything ticked by hand that it did not cover.
 *
 * Ticking is how a reader narrows what they are looking at; it is not an instruction to stop asking
 * the rest, and reading it as one left a search that had been narrowed months ago asking two
 * markets out of twenty-five.
 */
fun marketsToAsk(platforms: List<PlatformId>?, isCar: Boolean, shownMarkets: Set<PlatformId>): List<PlatformId> {
    val asked = platforms ?: MarketSets.platformsIn(
        if (isCar) MarketGroup.VEHICLES else MarketGroup.GENERAL,
        SearchCountries.current.countries,
    )
    val covered = coveredMarkets(isCar)
    return (asked + shownMarkets.filter { it in covered }).distinct()
}
