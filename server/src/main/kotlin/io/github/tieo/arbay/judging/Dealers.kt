package io.github.tieo.arbay.judging

import io.github.tieo.arbay.DataDir
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
                json.decodeFromString(Dealer.serializer(), Neuland.offers("offers/dealer?url=${url.encodeURLParameter()}", limitS = 150)).also {
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
