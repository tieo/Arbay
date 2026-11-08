package io.github.tieo.arbay.judging

import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.DriveHealth
import io.github.tieo.arbay.model.DriveVerdict
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.LiveAuction
import io.github.tieo.arbay.model.LiveAuctions
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.PriceSpread
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SoldMonth
import io.github.tieo.arbay.model.SoldPrices
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.relevance.RelevanceFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock

/**
 * What things sold for, from eBay.de's own list of sold offers, by model number, by size or by
 * words: the median and quartiles of what buyers paid with shipping, auctions apart from fixed
 * prices, by condition and by month. An offer sold to a proposal below its price is left out, since
 * the market hides what was paid, and so are parts-only offers.
 */
object SoldPriceReader {
    private val log = LoggerFactory.getLogger(SoldPriceReader::class.java)
    private const val KEEP_MS = 6L * 60 * 60 * 1000

    /** Sold offers per word set: the most one page of eBay's list holds. */
    private const val PER_PAGE = 240

    /** The sales an expected price needs behind it. */
    private const val ENOUGH = 5

    private class Kept(val at: Long, val sold: SoldPrices)

    private val kept = ConcurrentHashMap<String, Kept>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** One look at eBay's sold list at a time: each takes up to minutes in the shared browser. */
    private val turn = Mutex()
    private val background = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** What to look up, one of the three. */
    data class Ask(val model: String? = null, val capacityGb: Int? = null, val words: String? = null) {
        val key: String get() = model?.substringBefore('-') ?: capacityGb?.let { "$it GB" } ?: words!!.trim().lowercase()
    }

    suspend fun of(ask: Ask): SoldPrices {
        fresh(ask)?.let { return it }
        return turn.withLock { fresh(ask) ?: read(ask) }
    }

    private fun fresh(ask: Ask): SoldPrices? = kept[ask.key]?.takeIf { System.currentTimeMillis() - it.at < KEEP_MS }?.sold

    /** What is known already; where nothing is, a look is started and null answered. */
    private fun orStart(ask: Ask): SoldPrices? {
        fresh(ask)?.let { return it }
        if (inFlight.add(ask.key)) background.launch {
            try { of(ask) } catch (e: Exception) { log.warn("Sold prices of {} not read: {}", ask.key, e.message) } finally { inFlight.remove(ask.key) }
        }
        return null
    }

    private suspend fun read(ask: Ask): SoldPrices {
        val words = when {
            ask.model != null -> listOf(ask.model.substringBefore('-'))
            ask.capacityGb != null -> capacityWords(ask.capacityGb).let { listOf("$it HDD SATA", "$it Festplatte") }
            else -> listOf(ask.words!!.trim())
        }
        val found = LinkedHashMap<String, Listing>()
        words.forEach { w ->
            NeulandOffers.ask("ebay.de", w, PlatformId.EBAY_DE, "eBay.de", sold = true, perPage = PER_PAGE).listings.forEach { found[it.id] = it }
        }
        return stats(ask, found.values.toList()).also { kept[ask.key] = Kept(System.currentTimeMillis(), it) }
    }

    private fun capacityWords(gb: Int) = if (gb >= 1000 && gb % 1000 == 0) "${gb / 1000}TB" else "${gb}GB"

    /** The sales among [offers] that say what [ask] asks, added up. */
    internal fun stats(ask: Ask, offers: List<Listing>): SoldPrices {
        var hidden = 0
        var broken = 0
        var other = 0
        val prices = ArrayList<Pair<Listing, Double>>()
        for (l in offers) {
            if (!l.sold) continue
            if (l.soldToOffer) { hidden++; continue }
            val d = DriveOffers.read(l, null, DriveHealth())
            if (l.condition == Condition.PARTS_ONLY || d.health == DriveVerdict.FAILING) { broken++; continue }
            if (!matches(ask, l, d.capacityGb, d.external, d.lotPriceUnclear)) { other++; continue }
            val landed = l.effectivePrice.takeIf { it.currency == Currency.EUR && it.amount > 0 } ?: continue
            prices += l to landed.amount / 100.0
        }
        fun spread(of: (Listing) -> Boolean) = spreadOf(prices.filter { of(it.first) }.map { it.second })
        val months = prices.filter { it.first.soldDate != null }
            .groupBy { it.first.soldDate.toString().take(7) }
            .toSortedMap().map { (m, sales) -> SoldMonth(m, spreadOf(sales.map { it.second })) }
        return SoldPrices(
            asked = ask.key,
            all = spreadOf(prices.map { it.second }),
            auctions = spread { it.saleType == SaleType.AUCTION },
            buyNow = spread { it.saleType != SaleType.AUCTION },
            new = spread { it.condition == Condition.NEW },
            refurbished = spread { it.condition == Condition.REFURBISHED },
            used = spread { it.condition != Condition.NEW && it.condition != Condition.REFURBISHED },
            months = months,
            leftOutHiddenPrice = hidden,
            leftOutBroken = broken,
            leftOutOther = other,
        )
    }

    private fun matches(ask: Ask, l: Listing, capacityGb: Int?, external: Boolean?, lotUnclear: Boolean): Boolean {
        if (lotUnclear) return false
        return when {
            ask.model != null -> ask.model.substringBefore('-').let { m -> DriveText.models(l.title).any { it.substringBefore('-') == m } }
            ask.capacityGb != null -> capacityGb == ask.capacityGb && external != true && !SSD.containsMatchIn(l.title)
            else -> true
        }
    }

    private val SSD = Regex("""\b(ssd|nvme|m\.2)\b""", RegexOption.IGNORE_CASE)

    internal fun spreadOf(values: List<Double>): PriceSpread {
        if (values.isEmpty()) return PriceSpread()
        val sorted = values.sorted()
        fun at(q: Double): Double {
            val i = q * (sorted.size - 1)
            val lo = i.toInt()
            val hi = minOf(lo + 1, sorted.size - 1)
            return Math.round((sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo)) * 100) / 100.0
        }
        return PriceSpread(sorted.size, at(0.5), at(0.25), at(0.75))
    }

    // ── Auctions running now ──

    private class KeptAuctions(val at: Long, val listings: List<Listing>)

    private val auctions = ConcurrentHashMap<String, KeptAuctions>()
    private const val AUCTIONS_KEEP_MS = 10L * 60 * 1000

    /** The eBay.de auctions running for a saved search, soonest end first, each with what it can be
     *  expected to end at. The market takes minutes to answer for several words, so a list not known
     *  yet is asked for in the background and answered as still being read. */
    fun auctionsOf(product: TrackedProduct): LiveAuctions {
        val now = Clock.System.now()
        val known = auctions[product.id]
        val fresh = known != null && System.currentTimeMillis() - known.at < AUCTIONS_KEEP_MS
        val reading = !fresh && startAuctions(product)
        val list = known?.listings.orEmpty().filter { (it.auctionEndsAt ?: now) > now }.sortedBy { it.auctionEndsAt }.map { expectation(it, product.searchQuery) }
        return LiveAuctions(list, reading || "auctions ${product.id}" in inFlight)
    }

    private fun startAuctions(product: TrackedProduct): Boolean {
        val key = "auctions ${product.id}"
        if (inFlight.add(key)) background.launch {
            try {
                auctions[product.id] = KeptAuctions(System.currentTimeMillis(), turn.withLock { readAuctions(product.searchQuery) })
            } catch (e: Exception) {
                log.warn("Auctions of {} not read: {}", product.name, e.message)
            } finally {
                inFlight.remove(key)
            }
        }
        return true
    }

    private suspend fun readAuctions(query: SearchQuery): List<Listing> {
        val found = LinkedHashMap<String, Listing>()
        (listOf(query.positiveText) + query.aliases).forEach { w ->
            NeulandOffers.ask("ebay.de", w, PlatformId.EBAY_DE, "eBay.de", perPage = PER_PAGE).listings
                .filter { it.saleType == SaleType.AUCTION && it.auctionEndsAt != null }.forEach { found[it.id] = it }
        }
        return RelevanceFilter.partition(found.values.toList(), query).kept.map { DriveOffers.attach(it) }
    }

    private fun expectation(l: Listing, query: SearchQuery): LiveAuction {
        val d = l.drive
        val asks = listOfNotNull(
            d?.model?.let { Ask(model = it) },
            d?.capacityGb?.let { Ask(capacityGb = it) },
            Ask(words = query.positiveText).takeIf { d == null },
        )
        var pending = false
        val basis = asks.firstNotNullOfOrNull { ask ->
            val sold = orStart(ask)
            if (sold == null) pending = true
            sold?.takeIf { it.auctions.count >= ENOUGH }?.let { ask to it }
        }
        val current = l.effectivePrice.amount / 100.0
        return LiveAuction(
            listing = l,
            expectedEndEur = basis?.second?.auctions?.medianEur?.let { maxOf(it, current) },
            expectedFrom = basis?.let { "auctions of ${it.first.key}" },
            expectedSales = basis?.second?.auctions?.count ?: 0,
            expectedPending = basis == null && pending,
        )
    }
}
