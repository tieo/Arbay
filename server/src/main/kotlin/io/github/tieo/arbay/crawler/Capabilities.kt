package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.SearchQuery

/**
 * What a market can do, said out loud.
 *
 * `Crawler` has one method, so every difference between 27 markets is invisible to everything
 * outside the crawler itself: that only eBay knows which listings sold, that only Kleinanzeigen
 * prints related searches, that two markets in thirty can say when an ad was posted, that
 * AutoScout24 filters at the source while Marktplaats must be filtered afterwards. The app cannot
 * ask, so it cannot tell the person either — which is why a search shows no age for most listings
 * and no reason for a market returning nothing.
 *
 * Each capability below is an interface a crawler implements when it genuinely has it. Nothing is
 * inherited by default: a market that does not declare a capability does not have it, and the code
 * can be asked rather than read.
 */

/** Fetches more than the first page of results. A market that does not implement this returns one
 *  page, which for TruckScout24 and willhaben was true by accident rather than by design. */
interface FetchesEveryPage {
    /** Pages this market will fetch at most, before the search's own limit applies. */
    val pageLimit: Int get() = Int.MAX_VALUE
}

/** Turns vehicle criteria into the market's own URL parameters, so filtering happens at the source
 *  rather than over what came back. Which criteria differs per market, and saying so lets the app
 *  explain why a filter narrowed one market's results and not another's. */
interface FiltersAtTheSource {
    /** The criteria this market applies itself. Everything else is filtered afterwards. */
    val nativeCriteria: Set<Criterion>

    enum class Criterion { YEAR, MILEAGE, PRICE, POWER, GEARBOX, FUEL, BODY, CONDITION, SELLER }
}

/** Answers with listings that have already sold, which is what makes a price history possible.
 *  Only eBay does, across every market crawled. */
interface HasSoldListings

/** Prints the searches it considers related to the one asked — its own vocabulary for the thing,
 *  which is where query expansion gets its terms. Only Kleinanzeigen does. */
interface SuggestsRelatedSearches

/** Says when an ad was posted, so its age can be shown. Two markets of twenty-seven do. */
interface KnowsListingAge

/** Says where the thing is, so a distance can be measured from the searcher. */
interface KnowsLocation

/** Carries specs on the detail page that the result card omits — power, gearbox, doors, emission
 *  class — worth one extra fetch when a filter needs a field the card does not carry. */
interface HasDetailSpecs

/** Lists prices in a currency other than the euro, so amounts must be converted before they are
 *  compared or shown. */
interface PricesInOwnCurrency {
    val listingCurrency: io.github.tieo.arbay.model.Currency
}

/** Everything known about a market, gathered from what its crawler declares. Built by asking the
 *  crawler, never by writing a list by hand, so it cannot fall out of step with the code. */
data class MarketCapabilities(
    val paginates: Boolean,
    val pageLimit: Int?,
    val nativeCriteria: Set<FiltersAtTheSource.Criterion>,
    val soldListings: Boolean,
    val relatedSearches: Boolean,
    val listingAge: Boolean,
    val location: Boolean,
    val detailSpecs: Boolean,
    val currency: io.github.tieo.arbay.model.Currency?,
) {
    companion object {
        fun of(crawler: Crawler): MarketCapabilities = MarketCapabilities(
            paginates = crawler is FetchesEveryPage,
            pageLimit = (crawler as? FetchesEveryPage)?.pageLimit?.takeIf { it != Int.MAX_VALUE },
            nativeCriteria = (crawler as? FiltersAtTheSource)?.nativeCriteria ?: emptySet(),
            soldListings = crawler is HasSoldListings,
            relatedSearches = crawler is SuggestsRelatedSearches,
            listingAge = crawler is KnowsListingAge,
            location = crawler is KnowsLocation,
            detailSpecs = crawler is HasDetailSpecs,
            currency = (crawler as? PricesInOwnCurrency)?.listingCurrency,
        )
    }
}

/** Whether this market can answer the question at all, so the app can say "this market cannot tell
 *  you when the ad was posted" instead of leaving the field blank without explanation. */
fun Crawler.can(capability: Class<*>): Boolean = capability.isInstance(this)

/** Whether a search asks for something this market cannot do — a sold-listing search sent to a
 *  market with no sold listings returns nothing, and silence is indistinguishable from a block. */
fun Crawler.cannotAnswer(query: SearchQuery): String? = when {
    query.soldOnly && this !is HasSoldListings ->
        "${platformId.displayName} does not publish what sold"
    else -> null
}
