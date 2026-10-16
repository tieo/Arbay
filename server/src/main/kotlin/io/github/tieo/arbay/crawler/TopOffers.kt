package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.AdState
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.TopOffer
import io.github.tieo.arbay.model.TopOfferState
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * Every offer that ever ranked in a saved search's top ten, and whether the search still finds it.
 *
 * A search that stops returning its best offer says nothing about it: the offer is simply not in
 * the list, and nobody looks for what is not there. Live, 2026-10-16: the best van a Crafter search
 * had found was missing for days before anyone asked where it went. So each run looks for every
 * offer it ever ranked that high. One that did not come back is checked on its own page where the
 * market can say: taken down, it is [TopOfferState.GONE]; still up, or on a market that cannot
 * say, it is [TopOfferState.LOST], and reported until it comes back, since then the search itself
 * is losing it.
 */
object TopOffers {
    private val log = LoggerFactory.getLogger(TopOffers::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = DataDir.file("saved_search_top.json")

    /** How far down a run's ranking an offer is remembered from. */
    const val TOP = 10

    private val remembered: MutableMap<String, MutableMap<String, TopOffer>> =
        file.readStore(log) { json.decodeFromString<Map<String, Map<String, TopOffer>>>(it) }
            ?.mapValuesTo(HashMap()) { it.value.toMutableMap() } ?: HashMap()

    /** What the search remembers, best first. */
    fun of(productId: String): List<TopOffer> = synchronized(this) {
        remembered[productId]?.values.orEmpty().sortedByDescending { it.bestScore }
    }

    /**
     * One run's outcome: [ranked] is what it scored, best first, and [found] every offer it
     * returned. [stateOf] reads an ad's page, or answers null where the market cannot say.
     */
    suspend fun update(
        productId: String,
        ranked: List<Pair<Listing, Double>>,
        found: Set<String>,
        stateOf: suspend (TopOffer) -> AdState?,
    ): List<TopOffer> {
        val now = Clock.System.now()
        val missing = synchronized(this) {
            val known = remembered.getOrPut(productId) { HashMap() }
            ranked.take(TOP).forEach { (l, score) ->
                val before = known[l.id]
                known[l.id] = TopOffer(
                    listingId = l.id, url = l.url, title = l.title, priceEur = FitScore.priceEur(l),
                    bestScore = maxOf(score, before?.bestScore ?: 0.0), firstTopAt = before?.firstTopAt ?: now,
                    lastSeenAt = now, state = TopOfferState.FOUND,
                )
            }
            known.replaceAll { id, o -> if (id in found && o.state != TopOfferState.FOUND) o.copy(state = TopOfferState.FOUND, lastSeenAt = now) else o }
            known.values.filter { it.listingId !in found && it.state != TopOfferState.GONE }
        }
        val checked = missing.map { o ->
            val state = runCatching { stateOf(o) }.getOrNull()
            o.copy(state = if (state == AdState.DELETED) TopOfferState.GONE else TopOfferState.LOST, checkedAt = now, adState = state)
        }
        synchronized(this) {
            val known = remembered.getValue(productId)
            checked.forEach { known[it.listingId] = it }
            runCatching { file.writeTextAtomically(json.encodeToString(remembered.mapValues { it.value.toMap() })) }
                .onFailure { log.error("could not write the remembered top offers: {}", it.message) }
        }
        val lost = checked.filter { it.state == TopOfferState.LOST }
        lost.forEach { log.warn("top offer lost from {}: {} {} (ad {})", productId, it.title, it.url, it.adState ?: "state unknown") }
        return lost
    }

    /** Reads an offer's ad page through its market, where the market says whether an ad is up. */
    suspend fun adState(offer: TopOffer): AdState? {
        val platform = runCatching { io.github.tieo.arbay.model.PlatformId.valueOf(offer.listingId.substringBefore(':')) }.getOrNull() ?: return null
        val crawler = CrawlerRegistry.crawlerFor(platform) as? KnowsAdState ?: return null
        return crawler.adState(offer.url)
    }
}
