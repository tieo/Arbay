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
 * Also offers whose photos show up in other people's ads ([showsOtherAdsPhotos]), and offers whose
 * text sends the buyer off the market ([io.github.tieo.arbay.model.OffMarket]).
 *
 * Returns each such offer with the sentence saying why.
 */
fun likelyScams(listings: List<Listing>, views: Map<String, Int>, now: kotlin.time.Instant = Clock.System.now()): Map<String, String> {
    val forSale = listings.filter { !it.sold }.distinctBy { it.id }
    return seenTooOften(forSale, views, now) + showsOtherAdsPhotos(forSale) + sendsOffTheMarket(forSale)
}

private fun sendsOffTheMarket(forSale: List<Listing>): Map<String, String> =
    forSale.mapNotNull { l ->
        io.github.tieo.arbay.model.OffMarket.sign(l.description)?.let { l.id to "Takes buyers off the market: \"$it\"." }
    }.toMap()

/**
 * Offers that show photos of other people's ads: the server found one of their photos in an ad
 * placed elsewhere or by someone else. A gallery put together that way shows a vehicle the seller
 * does not have.
 */
private fun showsOtherAdsPhotos(forSale: List<Listing>): Map<String, String> =
    forSale.filter { it.photosAlsoIn.isNotEmpty() }.associate { l ->
        val places = l.photosAlsoIn.map { it.place ?: "another ad" }.distinct()
        l.id to "Shows photos from ${l.photosAlsoIn.size} other ad${if (l.photosAlsoIn.size == 1) "" else "s"} (${places.joinToString(", ")})."
    }

private fun seenTooOften(forSale: List<Listing>, views: Map<String, Int>, now: kotlin.time.Instant): Map<String, String> {
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
