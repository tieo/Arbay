package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/** The spread of what things sold for, shipping included, in euros. Null where nothing sold. */
@Serializable
data class PriceSpread(val count: Int = 0, val medianEur: Double? = null, val p25Eur: Double? = null, val p75Eur: Double? = null)

/** One month of sales, "2026-10". */
@Serializable
data class SoldMonth(val month: String, val spread: PriceSpread)

/** What a thing sold for on eBay.de lately, as the market's own list of sold offers shows it. */
@Serializable
data class SoldPrices(
    /** What was looked up: a model number, a size, or words. */
    val asked: String,
    val all: PriceSpread = PriceSpread(),
    val auctions: PriceSpread = PriceSpread(),
    val buyNow: PriceSpread = PriceSpread(),
    val new: PriceSpread = PriceSpread(),
    val refurbished: PriceSpread = PriceSpread(),
    val used: PriceSpread = PriceSpread(),
    val months: List<SoldMonth> = emptyList(),
    /** Sold to an offer accepted below the asking price, whose real price the market hides. */
    val leftOutHiddenPrice: Int = 0,
    /** Listed as parts only, or saying they are broken. */
    val leftOutBroken: Int = 0,
    /** Several together at one price, or another size or drive than the one asked. */
    val leftOutOther: Int = 0,
)

/** An auction running now, with what it can be expected to end at. */
@Serializable
data class LiveAuction(
    val listing: Listing,
    /** What it is expected to cost in the end, shipping included, in euros. */
    val expectedEndEur: Double? = null,
    /** What that rests on ("auctions of ST18000NM000J"). */
    val expectedFrom: String? = null,
    val expectedSales: Int = 0,
    /** The sales it rests on are still being read; asking again shortly has them. */
    val expectedPending: Boolean = false,
)

/** The auctions running for a saved search, as far as they have been read. */
@Serializable
data class LiveAuctions(
    val auctions: List<LiveAuction> = emptyList(),
    /** The market is still being asked; asking again shortly has them. */
    val reading: Boolean = false,
)
