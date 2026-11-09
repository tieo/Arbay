package io.github.tieo.arbay.judging

import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.DriveHealth
import io.github.tieo.arbay.model.DriveInterface
import io.github.tieo.arbay.model.DriveVerdict
import io.github.tieo.arbay.model.ModelWish
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
    data class Ask(
        val model: String? = null, val capacityGb: Int? = null, val words: String? = null,
        /** For a size: whether solid-state drives are asked, not hard disks. */
        val ssd: Boolean = false,
        /** For words: keeps only the sales that are what was asked. */
        val keep: ((Listing) -> Boolean)? = null,
        /** What kind of product it is, written beside each sale read for the price model. */
        val type: String? = null,
    ) {
        val key: String get() = model?.substringBefore('-') ?: capacityGb?.let { "$it GB" + if (ssd) " SSD" else "" } ?: words!!.trim().lowercase()
    }

    suspend fun of(ask: Ask): SoldPrices {
        fresh(ask)?.let { return it }
        return turn.withLock { fresh(ask) ?: read(ask) }
    }

    /** Writes down the auctions eBay.de lists as ended for [words], the unsold ones with their start price. */
    suspend fun recordEndedAuctions(words: String, type: String?) = turn.withLock {
        val ended = NeulandOffers.ask("ebay.de", words, PlatformId.EBAY_DE, "eBay.de", perPage = PER_PAGE, ended = true, auctions = true).listings
        SoldRows.live.add(words.trim().lowercase(), type, ended, unsoldToo = true)
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
            ask.capacityGb != null -> capacityWords(ask.capacityGb).let { if (ask.ssd) listOf("$it SSD NVMe", "$it SSD") else listOf("$it HDD SATA", "$it Festplatte") }
            else -> listOf(ask.words!!.trim())
        }
        val found = LinkedHashMap<String, Listing>()
        words.forEach { w ->
            NeulandOffers.ask("ebay.de", w, PlatformId.EBAY_DE, "eBay.de", sold = true, perPage = PER_PAGE).listings.forEach { found[it.id] = it }
        }
        SoldRows.live.add(ask.key, ask.type, found.values)
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
            ask.capacityGb != null -> capacityGb == ask.capacityGb && external != true && SSD.containsMatchIn(l.title) == ask.ssd
            else -> ask.keep?.invoke(l) ?: true
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
        val wishes = query.productWishes?.takeUnless { it.isEmpty }
        val model = wishes?.let { FitScore.namedModel(FitScore.folded(l.title), it) }
        // Sold prices are of working drives, so they say nothing of what a broken one ends at.
        if (l.condition == Condition.PARTS_ONLY || l.drive?.health == DriveVerdict.FAILING) return LiveAuction(l)
        var pending = false
        val basis = asksFor(l, query, model).firstNotNullOfOrNull { ask ->
            val sold = orStart(ask)
            if (sold == null) pending = true
            sold?.let { ask to it }
        }
        return answered(l, basis, model, pending)
    }

    /** What an auction's end can be read from, the closest kind of sale first. */
    private fun asksFor(l: Listing, query: SearchQuery, model: ModelWish?): List<Ask> {
        val d = l.drive
        val ssd = d?.driveInterface == DriveInterface.NVME || SSD.containsMatchIn(l.title)
        return listOfNotNull(
            d?.model?.let { Ask(model = it) },
            d?.capacityGb?.let { Ask(capacityGb = it, ssd = ssd) },
            model?.let { m ->
                val wishes = query.productWishes!!
                Ask(words = m.name, keep = { sold -> FitScore.namedModel(FitScore.folded(sold.title), wishes)?.name == m.name })
            },
            Ask(words = query.positiveText).takeIf { d == null && model == null },
        )
    }

    private fun answered(l: Listing, basis: Pair<Ask, SoldPrices>?, model: ModelWish?, pending: Boolean): LiveAuction {
        val current = FitScore.landedEur(l)
        // Auctions of the same thing say most; failing those, what it sold for at a fixed price.
        val spread = basis?.second?.let { if (it.auctions.count >= ENOUGH || it.all.count == 0) it.auctions else it.all }
        val from = basis?.let { (ask, sold) -> (if (spread === sold.auctions) "auctions of " else "sales of ") + ask.key }
        return LiveAuction(
            listing = l,
            expectedEndEur = spread?.medianEur?.let { maxOf(it, current) },
            expectedFrom = from.takeIf { spread?.medianEur != null },
            expectedSales = spread?.count ?: 0,
            expectedPending = spread?.medianEur == null && pending,
            expectedThin = spread != null && spread.count in 1 until ENOUGH,
            limitEur = model?.maxPriceEur,
        )
    }

    /** What the auction [l] can be expected to end at for a run nobody is waiting on: reads the sold
     *  prices it needs, however long that takes. */
    suspend fun expectedEnd(l: Listing, query: SearchQuery): LiveAuction {
        val wishes = query.productWishes?.takeUnless { it.isEmpty }
        val model = wishes?.let { FitScore.namedModel(FitScore.folded(l.title), it) }
        val basis = asksFor(l, query, model).firstNotNullOfOrNull { ask ->
            runCatching { of(ask) }.getOrNull()?.takeIf { it.all.count > 0 }?.let { ask to it }
        }
        return answered(l, basis, model, pending = false)
    }

    /** What [l] is judged at where a person is waiting: an auction at what is known of its end. */
    fun priceToJudge(l: Listing, query: SearchQuery): Double {
        if (l.saleType != SaleType.AUCTION) return FitScore.landedEur(l)
        return expectation(l, query).expectedEndEur ?: FitScore.landedEur(l)
    }
}
