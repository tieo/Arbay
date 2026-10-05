package io.github.tieo.arbay.model

import kotlin.math.abs
import kotlin.math.max

/**
 * One thing for sale, as found on every market that carries it.
 *
 * Dealers and private sellers put the same offer on several markets, and some markets publish
 * another's ads outright (Kleinanzeigen shows mobile.de's). Each copy is one listing; together
 * they are one offer, and [listings] holds every copy, the first being the one that leads.
 */
data class SameOffer(val listings: List<Listing>) {
    val lead: Listing get() = listings.first()
    val elsewhere: List<Listing> get() = listings.drop(1)

    companion object {
        /**
         * Folds copies of the same offer into one [SameOffer] each, keeping the order of
         * [listings]: an offer stands where its first copy stood, so whatever order the list was
         * sorted in still holds. The lead is the copy [price] finds cheapest, the first on a tie,
         * since the point is the cheapest way to get the thing.
         *
         * Two listings are copies when their titles are the same, letter for letter and long
         * enough to mean something: the same seller lists one item on every eBay locale, and in
         * one measured search 106 of 400 results were repeats of 42 titles. Listings on different
         * markets are copies as well when they ask the same price in the same currency, are in the
         * same place, and say the same thing: their titles share most of their words, or both
         * state the same first registration and mileage. Any vehicle fact both state and disagree
         * on keeps them apart, under either rule: a dealer titles every van of a kind alike. A
         * dealer with many vans at one address prices several of them alike, which is why price
         * and place alone are not enough.
         */
        fun group(listings: List<Listing>, price: (Listing) -> Long = { it.effectivePrice.amount }): List<SameOffer> {
            if (listings.size < 2) return listings.map { SameOffer(listOf(it)) }
            val parent = IntArray(listings.size) { it }
            fun root(i: Int): Int {
                var r = i
                while (parent[r] != r) r = parent[r]
                var c = i
                while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
                return r
            }
            fun join(i: Int, j: Int) { parent[root(j)] = root(i) }
            val facts = listings.map { Facts.of(it) }
            listings.indices
                .groupBy { listings[it].title.lowercase().filter { c -> c.isLetterOrDigit() } }
                .forEach { (key, indices) ->
                    // Too short to be sure two listings with it are the same thing.
                    if (key.length < 12) return@forEach
                    for (a in indices.indices) for (b in a + 1 until indices.size) {
                        val i = indices[a]
                        val j = indices[b]
                        if (!contradicts(listings[i].vehicle, listings[j].vehicle)) join(i, j)
                    }
                }
            // Only listings asking the same price can be copies by the second rule, so candidates
            // are compared within a price only, not all against all.
            val byPrice = listings.indices.groupBy { listings[it].price.currency to listings[it].price.amount }
            for (indices in byPrice.values) {
                if (indices.size < 2) continue
                for (a in indices.indices) for (b in a + 1 until indices.size) {
                    if (facts[indices[a]].matches(facts[indices[b]])) join(indices[a], indices[b])
                }
            }
            val members = listings.indices.groupBy { root(it) }
            return listings.indices
                .filter { members.getValue(root(it)).first() == it }
                .map { first ->
                    val copies = members.getValue(root(first)).map { listings[it] }
                    val cheapest = copies.minBy(price)
                    SameOffer(listOf(cheapest) + copies.filter { it !== cheapest })
                }
        }
    }

    private class Facts(val listing: Listing, val place: String?, val words: Set<String>) {
        fun matches(other: Facts): Boolean {
            if (listing.platformId == other.listing.platformId) return false
            if (place == null || place != other.place) return false
            if (contradicts(listing.vehicle, other.listing.vehicle)) return false
            return sameWords(words, other.words) || sameVehicle(listing.vehicle, other.listing.vehicle)
        }

        companion object {
            fun of(listing: Listing) = Facts(listing, placeOf(listing.location), wordsOf(listing.title))

            private fun placeOf(location: Location?): String? {
                location ?: return null
                if (location.zip == null && location.city == null && location.raw != null) {
                    return placeOf(Location.parse(location.raw).copy(raw = null))
                }
                location.zip?.filter { it.isLetterOrDigit() }?.takeIf { it.isNotEmpty() }?.let { return "zip:${it.lowercase()}" }
                return location.city?.lowercase()?.filter { it.isLetter() }?.takeIf { it.isNotEmpty() }?.let { "city:$it" }
            }

            private fun wordsOf(title: String): Set<String> =
                title.tidyTitle().lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
        }
    }
}

/** Most of the shorter title's words are in the longer one: a market that cuts titles short or
 *  appends a stock number still reads as the same ad. Three words at least, since two short
 *  titles like "VW Crafter" share everything and say nothing. */
private fun sameWords(a: Set<String>, b: Set<String>): Boolean {
    val shorter = minOf(a.size, b.size)
    if (shorter < 3) return false
    return (a intersect b).size >= shorter * 0.8
}

private fun sameVehicle(a: VehicleInfo?, b: VehicleInfo?): Boolean {
    a ?: return false
    b ?: return false
    return a.firstRegYear != null && a.firstRegYear == b.firstRegYear &&
        a.mileageKm != null && b.mileageKm != null && close(a.mileageKm, b.mileageKm)
}

/** Markets round mileage differently, so it may differ by a little; a year or an engine may not. */
private fun contradicts(a: VehicleInfo?, b: VehicleInfo?): Boolean {
    a ?: return false
    b ?: return false
    if (a.firstRegYear != null && b.firstRegYear != null && a.firstRegYear != b.firstRegYear) return true
    if (a.mileageKm != null && b.mileageKm != null && !close(a.mileageKm, b.mileageKm)) return true
    if (a.powerKw != null && b.powerKw != null && abs(a.powerKw - b.powerKw) > 1) return true
    return false
}

private fun close(a: Int, b: Int): Boolean = abs(a - b) <= max(100, max(a, b) / 100)
