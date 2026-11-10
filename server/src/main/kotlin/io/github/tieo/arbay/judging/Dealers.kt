package io.github.tieo.arbay.judging

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.chat.AuctionBids
import kotlin.time.Duration.Companion.minutes
import io.github.tieo.arbay.model.Dealer
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.neuland.Neuland
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import io.ktor.http.encodeURLParameter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Dealers as their own pages on the market say them, read through Neuland by the address of the
 * dealer's page an offer names, and kept a week: a dealer has many offers, and its name, hours and
 * ratings change slowly. One read at a time, since each is one or two pages in the shared browser.
 */
object Dealers {
    private val log = LoggerFactory.getLogger(Dealers::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val file = DataDir.file("dealers.json")
    private const val KEEP_MS = 7L * 24 * 3600 * 1000

    @Serializable
    private data class Kept(val at: Long, val dealer: Dealer)

    private val kept: MutableMap<String, Kept> = ConcurrentHashMap(
        file.readStore(log) { json.decodeFromString<Map<String, Kept>>(it) } ?: emptyMap(),
    )
    private val turn = Mutex()
    private val background = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val asking: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The dealer behind [listing], where it is one and its page is known. */
    fun pageOf(listing: Listing): String? = io.github.tieo.arbay.model.dealerPage(listing)

    /** What is kept of the dealer at [url], without reading anything. */
    fun cached(url: String?): Dealer? = url?.let { kept[it]?.dealer }

    /** The dealer at [url], read now unless kept within the week; null where its pages could not be read. */
    suspend fun of(url: String): Dealer? {
        fresh(url)?.let { return it }
        return turn.withLock {
            fresh(url) ?: try {
                val market = read(url)
                // Google's lowest reviews are sorted in the signed-in browser a last-second bid uses,
                // so near a bid the dealer goes without them and is not kept, to be read again later.
                if (AuctionBids.busySoon(AuctionBids.OPEN_BEFORE + 10.minutes)) return@withLock market
                market.copy(google = onGoogle(market)).also {
                    kept[url] = Kept(System.currentTimeMillis(), it)
                    persist()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("dealer at {} not read: {}", url, e.message)
                null
            }
        }
    }

    private suspend fun read(url: String) = json.decodeFromString(Dealer.serializer(), Neuland.offers("offers/dealer?url=${url.encodeURLParameter()}", limitS = 150))

    /**
     * The dealer's Google listing, looked up by its name and town. Google answers the first place for
     * the words, so it counts only at the dealer's own postcode or, where either lacks one, its town.
     */
    private suspend fun onGoogle(market: Dealer): Dealer? {
        val name = market.name ?: return null
        val town = market.address?.town ?: return null
        val words = java.net.URLEncoder.encode("$name $town", Charsets.UTF_8)
        val found = try {
            read("https://www.google.com/maps/search/$words?hl=de")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.info("no Google listing for {}: {}", name, e.message)
            return null
        }
        return found.takeIf { samePlace(market.address, it.address) }
            ?: null.also { log.info("Google's place for {} is {} at {}, not the dealer", name, found.name, found.address) }
    }

    internal fun samePlace(a: io.github.tieo.arbay.model.DealerAddress?, b: io.github.tieo.arbay.model.DealerAddress?): Boolean {
        if (a == null || b == null) return false
        val pa = a.postcode?.trim()
        val pb = b.postcode?.trim()
        if (pa != null && pb != null) return pa == pb
        fun f(t: String?) = t?.let { FitScore.folded(it).trim() }
        return f(a.town) != null && f(a.town) == f(b.town)
    }

    /** Reads, one after another in the background, the dealers of [listings] not kept yet. */
    fun readSoon(listings: Collection<Listing>) {
        val urls = listings.mapNotNull(::pageOf).distinct().filter { fresh(it) == null && asking.add(it) }
        if (urls.isNotEmpty()) background.launch { urls.forEach { try { of(it) } finally { asking.remove(it) } } }
    }

    private fun fresh(url: String) = kept[url]?.takeIf { System.currentTimeMillis() - it.at < KEEP_MS }?.dealer

    private fun persist() {
        runCatching { file.writeTextAtomically(json.encodeToString(kept.toMap())) }
            .onFailure { log.warn("dealers not kept: {}", it.message) }
    }
}
