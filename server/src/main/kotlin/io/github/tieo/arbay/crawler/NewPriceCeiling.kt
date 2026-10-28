package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * The price every market is asked up to when a search names none: what the thing costs new.
 *
 * A search is for the cheapest good offers, and a used one dearer than the same thing new is
 * never one of them, yet a market asked without a ceiling answers with every price it has: for
 * "2TB NVMe" Kleinanzeigen sends 348 offers, of which 96 cost less than the drive new. The price
 * comparers [shops] answer first, the new price is read off the offers of theirs the search kept,
 * and the other markets are asked with it as their ceiling, which each of them filters on itself.
 *
 * The price taken is the [RANK]th cheapest, not the cheapest: a comparer lists the odd accessory
 * that passes for the thing, and one of them at 12 € would cut every real offer away. Fewer than
 * [RANK] offers is no price at all, and the markets are asked without a ceiling, as before.
 *
 * Offers below the search's own [floor] are not the thing by the search's own word: a comparer
 * lists a shower toilet's O-rings and filters at 2 € before the toilet, and a ceiling read off
 * them asked every market for nothing dearer than 10 € while the search wanted 150 € and up.
 */
class NewPriceCeiling(val shops: Set<PlatformId>, private val floor: Money? = null) {
    private val answers = ConcurrentHashMap<PlatformId, List<Listing>>()
    private val ceiling = CompletableDeferred<Money?>()

    /** What [platform] answered, or nothing for a market that failed. The first answer counts:
     *  a market reports again once its listings are placed on the map. */
    fun answered(platform: PlatformId, listings: List<Listing>) {
        if (platform !in shops) return
        answers.putIfAbsent(platform, listings)
        if (answers.keys.containsAll(shops)) ceiling.complete(of(answers.values.flatten(), floor))
    }

    /** The ceiling, once every comparer has answered. */
    suspend fun await(): Money? = ceiling.await()

    companion object {
        /** The markets that list what a thing costs new across shops. */
        val COMPARERS = setOf(PlatformId.GEIZHALS, PlatformId.IDEALO)
        const val RANK = 3

        /** A ceiling for a search over [platforms], or null where there is nothing to read the new
         *  price from or nothing to ask with it. */
        fun forSearch(platforms: List<PlatformId>, floor: Money? = null): NewPriceCeiling? {
            val shops = platforms.filter { it in COMPARERS }.toSet()
            return if (shops.isEmpty() || platforms.all { it in shops }) null else NewPriceCeiling(shops, floor)
        }

        fun of(listings: List<Listing>, floor: Money? = null): Money? {
            val least = floor?.let { ExchangeRates.convert(it.amount, it.currency.name, "EUR") } ?: 1L
            val prices = listings.map { ExchangeRates.convert(it.effectivePrice.amount, it.effectivePrice.currency.name, "EUR") }
                .filter { it >= least }
                .sorted()
            return prices.getOrNull(RANK - 1)?.let { Money(it, Currency.EUR) }
        }
    }
}
