package io.github.tieo.arbay.results

import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.DropReason
import io.github.tieo.arbay.model.DroppedListing
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.label
import io.github.tieo.arbay.model.withPriceRangeEur
import kotlin.math.ceil
import kotlin.math.floor

/**
 * What the reader has narrowed a results screen to: a price band, the conditions and ways of being
 * sold they are looking at, and whether only what is new since they last looked.
 *
 * A [band] of null is the whole range, whatever the results turn out to hold, so results still
 * arriving widen it rather than narrowing what the reader asked for. Prices are in whole units of
 * the display currency.
 */
data class Narrowing(
    val band: ClosedFloatingPointRange<Float>? = null,
    val conditions: Set<Condition> = emptySet(),
    val unstatedCondition: Boolean = true,
    val saleTypes: Set<SaleType> = emptySet(),
    val unstatedSaleType: Boolean = true,
    val newOnly: Boolean = false,
    val hiddenConditions: Set<Condition> = emptySet(),
    val hiddenSaleTypes: Set<SaleType> = emptySet(),
) {
    companion object {
        /** What a saved search was last narrowed to. "New only" is never saved: the backlog it
         *  names is gone the moment the search is opened. */
        fun of(saved: SearchQuery?): Narrowing {
            saved ?: return Narrowing()
            val low = saved.minPrice?.amount?.div(100)?.toFloat()
            val high = saved.maxPrice?.amount?.div(100)?.toFloat()
            return Narrowing(
                band = if (low == null && high == null) null else (low ?: 0f)..(high ?: Float.MAX_VALUE),
                conditions = saved.condition?.toSet() ?: emptySet(),
                unstatedCondition = saved.conditionUnstated,
                saleTypes = saved.saleTypes?.toSet() ?: emptySet(),
                unstatedSaleType = saved.saleTypeUnstated,
                hiddenConditions = saved.hiddenConditions.toSet(),
                hiddenSaleTypes = saved.hiddenSaleTypes.toSet(),
            )
        }
    }
}

/** Whether a listing in [condition] belongs on a screen narrowed to [wanted]. No condition picked is
 *  every condition. A listing whose market never said is its own answer ("Not stated") rather than
 *  being counted as used: a broken drive sold for parts and a working one were both "not new", so a
 *  search could not be told to leave the broken ones out. */
fun conditionMatches(wanted: Set<Condition>, unstated: Boolean, condition: Condition?, hidden: Set<Condition> = emptySet()): Boolean = when {
    condition != null && condition in hidden -> false
    wanted.isEmpty() -> condition != null || unstated
    condition == null -> unstated
    else -> condition in wanted
}

/** Whether a listing sold this way belongs on a screen narrowed to [wanted]. */
fun saleTypeMatches(wanted: Set<SaleType>, unstated: Boolean, saleType: SaleType?, hidden: Set<SaleType> = emptySet()): Boolean = when {
    saleType != null && saleType in hidden -> false
    wanted.isEmpty() -> saleType != null || unstated
    saleType == null -> unstated
    else -> saleType in wanted
}

/** Median of price amounts (cents, already in the display currency) as Money; null when empty. */
fun medianMoney(prices: List<Long>, currency: Currency): Money? {
    if (prices.isEmpty()) return null
    val sorted = prices.sorted()
    return Money(sorted[sorted.size / 2], currency)
}

/** A listing's price as every screen shows and compares it: landed, in the display currency, cents. */
fun Listing.displayAmount(): Long = DisplayCurrency.convert(comparablePrice.amount, comparablePrice.currency.name)

/**
 * The listings on a results screen once the reader's narrowing is applied, with everything the
 * screen says about them.
 */
class Narrowed(
    /** Every listing for sale, before the reader's narrowing. */
    val allActive: List<Listing>,
    /** The cheapest and dearest price there, in whole display units: the band's track. The true
     *  ends, not a percentile trim, so a band that was never moved filters nothing. */
    val priceMin: Float,
    val priceMax: Float,
    /** The reader's band held inside what the results actually hold. */
    val priceRange: ClosedFloatingPointRange<Float>,
    val priceFiltered: Boolean,
    /** What is inside the band. */
    val active: List<Listing>,
    /** How many of those are in each condition and each way of being sold, null for the ones whose
     *  market never said, so a choice nothing is in is not offered. */
    val conditionCounts: Map<Condition?, Int>,
    val saleTypeCounts: Map<SaleType?, Int>,
    /** What is on screen: inside the band, in a wanted condition and sale type, new if asked. */
    val displayed: List<Listing>,
) {
    /** Bounds compare in whole currency units, and an end resting on the end of the track is no
     *  bound. Comparing cent for cent would shave a cent off a boundary and drop the very listing
     *  the reader narrowed onto. */
    fun inPriceRange(amount: Long): Boolean = withinBand(amount, priceRange, priceMin, priceMax)
}

private fun withinBand(amount: Long, range: ClosedFloatingPointRange<Float>, trackMin: Float, trackMax: Float): Boolean {
    val units = amount / 100.0
    val minOk = range.start <= trackMin || units >= floor(range.start.toDouble())
    val maxOk = range.endInclusive >= trackMax || units <= ceil(range.endInclusive.toDouble())
    return minOk && maxOk
}

/** Applies [narrowing] to the listings a search has on screen ([listings], sold ones included). */
fun narrow(listings: List<Listing>, narrowing: Narrowing, newListingIds: Set<String>): Narrowed {
    val allActive = listings.filter { !it.sold }
    val prices = allActive.map { it.displayAmount() }.sorted()
    val priceMin = (prices.firstOrNull() ?: 0L) / 100f
    val priceMax = ((prices.lastOrNull() ?: 100_000L) / 100f).coerceAtLeast(priceMin + 1f)
    val priceRange = narrowing.band
        ?.let { band -> band.start.coerceIn(priceMin, priceMax)..band.endInclusive.coerceIn(priceMin, priceMax) }
        ?.takeIf { it.start <= it.endInclusive }
        ?: priceMin..priceMax
    val priceFiltered = priceRange.start > priceMin || priceRange.endInclusive < priceMax
    val active = if (!priceFiltered) allActive
        else allActive.filter { withinBand(it.displayAmount(), priceRange, priceMin, priceMax) }
    val displayed = active
        .filter { conditionMatches(narrowing.conditions, narrowing.unstatedCondition, it.condition, narrowing.hiddenConditions) }
        .filter { saleTypeMatches(narrowing.saleTypes, narrowing.unstatedSaleType, it.saleType, narrowing.hiddenSaleTypes) }
        .filter { !narrowing.newOnly || it.id in newListingIds }
    return Narrowed(
        allActive = allActive,
        priceMin = priceMin,
        priceMax = priceMax,
        priceRange = priceRange,
        priceFiltered = priceFiltered,
        active = active,
        conditionCounts = active.groupingBy { it.condition }.eachCount(),
        saleTypeCounts = active.groupingBy { it.saleType }.eachCount(),
        displayed = displayed,
    )
}

/** The band to save on a search: an end resting on the end of the track is no bound. Saved as the
 *  price it happened to sit at, raising only the minimum also kept a maximum of today's dearest
 *  listing, and for a car search that went to the markets as a price cap. */
fun SearchQuery.withBand(band: ClosedFloatingPointRange<Float>, narrowed: Narrowed): SearchQuery {
    val min = band.start.takeIf { it > narrowed.priceMin }?.toInt()
    val max = band.endInclusive.takeIf { it < narrowed.priceMax }?.toInt()
    return withPriceRangeEur(min, max)
}

/** Why a group of listings is missing from the screen. The reader's own choices each have one
 *  action that puts them back; what the search itself removed is grouped by its reason. */
sealed interface HiddenKind {
    data object YouHid : HiddenKind
    data object BlockedWords : HiddenKind
    data object PriceBand : HiddenKind
    data object Condition : HiddenKind
    data object SaleType : HiddenKind
    data object NotNew : HiddenKind
    data class Search(val reason: DropReason, val detail: String?) : HiddenKind
}

/** A group of listings missing from the screen, what took them, and why. */
data class Hidden(val kind: HiddenKind, val label: String, val why: String, val listings: List<Listing>)

/**
 * Every way a listing can be missing from a results screen, each with what took it: the reader's
 * bin, their blocked words (here and on the server, which never sends one on), their price band,
 * condition, sale type and "new only", and whatever the search itself removed, one group per
 * reason, and where one reason covers several things, one group per thing: "a vehicle criterion" is
 * not an answer, "its mileage" is.
 */
fun hiddenListings(
    narrowed: Narrowed,
    narrowing: Narrowing,
    fetched: List<Listing>,
    marketBasis: List<Listing>,
    bannedIds: Set<String>,
    blockedTerms: List<String>,
    droppedBySearch: List<DroppedListing>,
    newListingIds: Set<String>,
): List<Hidden> = buildList {
    val banned = fetched.filter { it.id in bannedIds }
    val byWord = fetched.filter { it.id !in bannedIds && it !in marketBasis } +
        droppedBySearch.filter { it.reason == DropReason.BLOCKED_WORD }.map { it.listing }
    val outOfBand = if (!narrowed.priceFiltered) emptyList()
        else narrowed.allActive.filterNot { narrowed.inPriceRange(it.displayAmount()) }
    val inCondition = { l: Listing -> conditionMatches(narrowing.conditions, narrowing.unstatedCondition, l.condition, narrowing.hiddenConditions) }
    val wrongCondition = narrowed.active.filterNot(inCondition)
    val wrongSaleType = narrowed.active.filter(inCondition)
        .filterNot { saleTypeMatches(narrowing.saleTypes, narrowing.unstatedSaleType, it.saleType, narrowing.hiddenSaleTypes) }
    val notNew = if (!narrowing.newOnly) emptyList()
        else narrowed.active.filter { inCondition(it) && it.id !in newListingIds }

    if (banned.isNotEmpty()) add(Hidden(HiddenKind.YouHid, "you hid",
        "Listings you sent away with the bin on their card.", banned))
    if (byWord.isNotEmpty()) add(Hidden(HiddenKind.BlockedWords, "your blocked words",
        "Carrying one of your blocked words: " + blockedTerms.joinToString(", "), byWord))
    if (outOfBand.isNotEmpty()) add(Hidden(HiddenKind.PriceBand, "outside your price band",
        "Priced outside the band this search is narrowed to.", outOfBand))
    if (wrongCondition.isNotEmpty()) add(Hidden(HiddenKind.Condition, "the other condition",
        "You are looking at " + (narrowing.conditions.takeIf { it.isNotEmpty() }
            ?.sortedBy { it.ordinal }?.joinToString(", ") { it.label.lowercase() }
            ?: "only what states its condition") + ".", wrongCondition))
    if (wrongSaleType.isNotEmpty()) add(Hidden(HiddenKind.SaleType, "sold the other way",
        "You are looking at " + (narrowing.saleTypes.takeIf { it.isNotEmpty() }
            ?.sortedBy { it.ordinal }?.joinToString(", ") { it.label.lowercase() }
            ?: "only what says how it is sold") + ".", wrongSaleType))
    if (notNew.isNotEmpty()) add(Hidden(HiddenKind.NotNew, "not new since you last looked",
        "You are looking at what this search found since you last opened it.", notNew))
    droppedBySearch.filterNot { it.reason == DropReason.BLOCKED_WORD }
        .groupBy { it.reason to it.detail }
        .forEach { (key, entries) ->
            val (reason, detail) = key
            add(Hidden(HiddenKind.Search(reason, detail), detail ?: reason.label,
                explainDropReason(reason) + (detail?.let { " Taken by: $it." } ?: ""),
                entries.map { it.listing }))
        }
}

/** The blocked word that caught a listing, which is the word to drop when the reader disagrees. */
fun wordThatCaught(listing: Listing, blockedTerms: List<String>): String? {
    val text = "${listing.title} ${listing.description.orEmpty()}".lowercase()
    return blockedTerms.firstOrNull { text.contains(it.lowercase()) }
}
