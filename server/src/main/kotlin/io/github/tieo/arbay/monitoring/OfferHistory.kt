package io.github.tieo.arbay.monitoring

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SaleType
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * What the watches saw happen to each offer, one line per event in a file that only grows: when an
 * offer appeared, every change of its price, when a run stopped keeping it and when it came back,
 * and how its ad ended where its page was read.
 *
 * It is what tells how many new offers a search gets a week, how long they stay and how far their
 * asks fall, on the markets that publish no sold list. None of that can be worked out afterwards,
 * so it is written down as it happens. The file is also its own memory: a restart replays it.
 *
 * An offer missing from a run counts only where its market answered that run, and "not kept" means
 * the run's own result: an offer can be up on its market and ruled out by the search.
 */
class OfferHistory(private val file: File) {
    enum class Event { FIRST_SEEN, PRICE_CHANGED, NOT_KEPT, KEPT_AGAIN, AD_ENDED }

    @Serializable
    data class Entry(
        val at: Instant,
        val search: String,
        val offer: String,
        val market: String,
        val event: Event,
        /** The price without shipping, in cents of [currency]. */
        val priceCents: Long? = null,
        /** The price with shipping, in cents of [currency]. */
        val landedCents: Long? = null,
        val currency: Currency? = null,
        val saleType: SaleType? = null,
        val title: String? = null,
        /** How an [Event.AD_ENDED] ad's page reads. */
        val how: String? = null,
    )

    private data class Last(val priceCents: Long, val landedCents: Long, val currency: Currency, val kept: Boolean)

    private val log = LoggerFactory.getLogger(OfferHistory::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val last = HashMap<String, HashMap<String, Last>>()

    init {
        if (file.exists()) file.forEachLine { line ->
            runCatching { json.decodeFromString(Entry.serializer(), line) }.getOrNull()?.let(::replay)
        }
    }

    private fun replay(e: Entry) {
        val offers = last.getOrPut(e.search) { HashMap() }
        when (e.event) {
            Event.FIRST_SEEN, Event.PRICE_CHANGED, Event.KEPT_AGAIN ->
                if (e.priceCents != null && e.landedCents != null && e.currency != null) offers[e.offer] = Last(e.priceCents, e.landedCents, e.currency, kept = true)
            Event.NOT_KEPT -> offers[e.offer]?.let { offers[e.offer] = it.copy(kept = false) }
            Event.AD_ENDED -> {}
        }
    }

    /** One run of [search] kept [found]; [unanswered] names the markets that gave it no answer. */
    @Synchronized
    fun update(search: String, found: Collection<Listing>, unanswered: Set<String>, now: Instant = Clock.System.now()) {
        val offers = last.getOrPut(search) { HashMap() }
        val entries = ArrayList<Entry>()
        for (l in found) {
            val before = offers[l.id]
            val landed = l.effectivePrice
            fun entry(event: Event, title: String? = null) = Entry(
                now, search, l.id, l.platformId.name, event, l.price.amount, landed.amount, l.price.currency, l.saleType, title,
            )
            when {
                before == null -> entries += entry(Event.FIRST_SEEN, l.title)
                before.priceCents != l.price.amount || before.landedCents != landed.amount || before.currency != l.price.currency -> entries += entry(Event.PRICE_CHANGED)
                !before.kept -> entries += entry(Event.KEPT_AGAIN)
            }
        }
        val kept = found.mapTo(HashSet()) { it.id }
        offers.filter { (id, o) -> o.kept && id !in kept && id.substringBefore(':') !in unanswered }.keys.forEach { id ->
            entries += Entry(now, search, id, id.substringBefore(':'), Event.NOT_KEPT)
        }
        write(entries)
    }

    /** The page of [offer], which [search] found, reads [how]. */
    @Synchronized
    fun ended(search: String, offer: String, how: String, now: Instant = Clock.System.now()) =
        write(listOf(Entry(now, search, offer, offer.substringBefore(':'), Event.AD_ENDED, how = how)))

    private fun write(entries: List<Entry>) {
        if (entries.isEmpty()) return
        entries.forEach(::replay)
        runCatching { file.appendText(entries.joinToString("") { json.encodeToString(Entry.serializer(), it) + "\n" }) }
            .onFailure { log.error("could not write the offer history: {}", it.message) }
    }

    companion object {
        val live by lazy { OfferHistory(DataDir.file("offer_history.jsonl")) }
    }
}
