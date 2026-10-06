package io.github.tieo.arbay.results

import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money

/**
 * Whether a price is good, in the figures that answer it: the cheapest offer, the middle one, the
 * dearest, how they spread, and what the same thing sold for where a market says.
 *
 * Every amount is landed and in the display currency, as the offers themselves are shown.
 */
data class PriceSummary(
    val count: Int,
    val cheapest: Money?,
    val middle: Money?,
    val dearest: Money?,
    /** How many offers fall in each of [BUCKETS] equal price steps from cheapest to dearest. */
    val spread: List<Int>,
    /** The offers that cost the least, which the accent marks. */
    val cheapestIds: Set<String>,
    val sold: Sold?,
) {
    /** What the same thing went for: the middle sold price and how many sales it is taken from. */
    data class Sold(val middle: Money, val count: Int)

    /** How far [price] sits below the middle offer, as a share of it; negative when above. */
    fun belowMiddle(price: Money): Double? {
        val m = middle?.amount?.takeIf { it > 0 } ?: return null
        return (m - price.amount).toDouble() / m
    }

    companion object {
        const val BUCKETS = 12
    }
}

/** The price picture of what is on screen ([offers], for sale) and of what has sold ([sold]). */
fun priceSummary(offers: List<Listing>, sold: List<Listing> = emptyList()): PriceSummary {
    val currency = Currency.valueOf(DisplayCurrency.current)
    val priced = offers.filter { !it.sold }.map { it to it.displayAmount() }.sortedBy { it.second }
    val amounts = priced.map { it.second }
    val low = amounts.firstOrNull()
    val high = amounts.lastOrNull()
    val spread = MutableList(PriceSummary.BUCKETS) { 0 }
    if (low != null && high != null) {
        val width = ((high - low).coerceAtLeast(1)).toDouble() / PriceSummary.BUCKETS
        amounts.forEach { amount ->
            val bucket = ((amount - low) / width).toInt().coerceIn(0, PriceSummary.BUCKETS - 1)
            spread[bucket]++
        }
    }
    val soldAmounts = sold.map { it.displayAmount() }.sorted()
    return PriceSummary(
        count = amounts.size,
        cheapest = low?.let { Money(it, currency) },
        middle = medianMoney(amounts, currency),
        dearest = high?.let { Money(it, currency) },
        spread = spread,
        cheapestIds = priced.takeWhile { it.second == low }.map { it.first.id }.toSet(),
        sold = medianMoney(soldAmounts, currency)?.let { PriceSummary.Sold(it, soldAmounts.size) },
    )
}
