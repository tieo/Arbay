package io.github.tieo.arbay.results

import io.github.tieo.arbay.model.Listing
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Offers that look like scams from the outside: online for a month or more, cheaper than most of
 * the search, and looked at far more often than the others yet still for sale. Many buyers saw the
 * price, and none of them bought.
 *
 * Measured against the search itself, since how often an ad is seen depends on what it is: in a
 * search for a Pixel 9 Pro XL, offers saw 5 to 25 views a day, and the one at 400 € with 2,053 views
 * after 59 days had five times the views of the other ad posted that day.
 *
 * Returns each such offer with the sentence saying why.
 */
fun likelyScams(listings: List<Listing>, views: Map<String, Int>, now: kotlin.time.Instant = Clock.System.now()): Map<String, String> {
    val forSale = listings.filter { !it.sold }.distinctBy { it.id }
    if (forSale.size < 5) return emptyMap()
    val prices = forSale.map { it.displayAmount() }.sorted()
    val middlePrice = prices[prices.size / 2]
    val settled = forSale.filter { l -> l.listingDate?.let { now - it >= 14.days } == true }.mapNotNull { views[it.id] }.sorted()
    if (settled.size < 3) return emptyMap()
    val middleViews = settled[settled.size / 2]
    return forSale.mapNotNull { l ->
        val seen = views[l.id] ?: return@mapNotNull null
        val posted = l.listingDate ?: return@mapNotNull null
        val age = now - posted
        if (age < 30.days || l.displayAmount() >= middlePrice || seen < middleViews * 3) return@mapNotNull null
        l.id to "Seen $seen times in ${age.inWholeDays} days and still for sale below the middle price; offers here are usually seen about $middleViews times."
    }.toMap()
}
