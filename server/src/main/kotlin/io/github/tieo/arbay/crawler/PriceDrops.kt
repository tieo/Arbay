package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PriceDrop
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.landedPrice
import io.github.tieo.arbay.repo.ImportSettingsStore
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The price each saved search last found every offer at, and the offers that have come down since
 * the search was last opened.
 *
 * A seller who lowers an ad the watch already knows makes nothing new, so no run says a word about
 * it, while a van 2,000 € cheaper than last week is exactly what a watch is for. So every run remembers what each offer it found costs, and an offer found for less than last
 * time becomes a [PriceDrop] until the search is opened. The drop keeps the price from before the
 * first cut, so an offer lowered twice reads as the whole way down; one raised back to that price
 * or above has not dropped at all, and its drop goes.
 *
 * A cut is read from the price in the market's own currency, shipping included, since an exchange
 * rate that moves overnight would otherwise lower every Swiss and Polish offer a little each day.
 * What a drop states is the landed price in EUR, the one the watch's alert rules and the MCP use.
 * An auction's price is a bid, which only rises and is no offer, so auctions are not watched.
 */
object PriceDrops {
    private val log = LoggerFactory.getLogger(PriceDrops::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = DataDir.file("saved_search_prices.json")

    /** How long an offer's price is remembered after it was last found, so a market that fails
     *  for a few runs does not make its offers first sightings again, and sold ones still go. */
    private val MEMORY = 60.days

    /** One offer's price as a run found it: in its own currency to tell a cut, in EUR to state it. */
    @Serializable
    private data class Seen(val amount: Long, val currency: Currency, val eurCents: Long?, val at: Instant)

    @Serializable
    private data class Search(val seen: Map<String, Seen> = emptyMap(), val drops: Map<String, PriceDrop> = emptyMap())

    private val searches: MutableMap<String, Search> =
        file.readStore(log) { json.decodeFromString<Map<String, Search>>(it) }?.toMutableMap() ?: HashMap()

    /** The drops [productId] has waiting, biggest cut first. */
    fun of(productId: String): List<PriceDrop> = synchronized(this) {
        searches[productId]?.drops?.values.orEmpty().sortedByDescending { it.fromEur - it.toEur }
    }

    /**
     * One run's offers of [productId]: remembers each one's price and answers the offers that came
     * down on this run. [eurCents] states a landed price in EUR, or null where no rate is known.
     */
    fun update(
        productId: String,
        found: Collection<Listing>,
        now: Instant = Clock.System.now(),
        eurCents: (Listing) -> Long? = { SavedSearchMonitor.eurCents(it.landedPrice(ImportSettingsStore.current)) },
    ): List<PriceDrop> {
        val cut = mutableListOf<PriceDrop>()
        synchronized(this) {
            val before = searches[productId] ?: Search()
            val seen = HashMap(before.seen.filterValues { now - it.at < MEMORY })
            val drops = HashMap(before.drops)
            for (l in found) {
                if (l.saleType == SaleType.AUCTION) continue
                val price = l.effectivePrice
                val eur = eurCents(l)
                val last = seen[l.id]
                seen[l.id] = Seen(price.amount, price.currency, eur, now)
                if (eur == null || last == null || last.currency != price.currency) continue
                val pending = drops[l.id]
                when {
                    price.amount < last.amount -> {
                        val from = pending?.fromEur ?: last.eurCents?.let { it / 100.0 } ?: continue
                        val drop = PriceDrop(l.id, l.url, l.title, fromEur = from, toEur = eur / 100.0, at = now)
                        if (drop.toEur < drop.fromEur) { drops[l.id] = drop; cut += drop } else drops.remove(l.id)
                    }
                    pending == null || price.amount == last.amount -> {}
                    eur / 100.0 >= pending.fromEur -> drops.remove(l.id)
                    else -> drops[l.id] = pending.copy(toEur = eur / 100.0)
                }
            }
            searches[productId] = Search(seen, drops)
            save()
        }
        cut.forEach { log.info("price drop in {}: {} {} € -> {} € {}", productId, it.title.take(60), it.fromEur.toLong(), it.toEur.toLong(), it.url) }
        return cut
    }

    /** The search was opened: its drops have been seen. The prices stay, to tell the next cut. */
    fun opened(productId: String) = synchronized(this) {
        val search = searches[productId] ?: return@synchronized
        if (search.drops.isEmpty()) return@synchronized
        searches[productId] = search.copy(drops = emptyMap())
        save()
    }

    /** Forgets every search not in [live], so a deleted one leaves no prices behind. */
    fun retain(live: Set<String>) = synchronized(this) {
        if (searches.keys.retainAll(live)) save()
    }

    private fun save() {
        runCatching { file.writeTextAtomically(json.encodeToString(searches.toMap())) }
            .onFailure { log.error("could not write the remembered offer prices: {}", it.message) }
    }
}
