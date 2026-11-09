package io.github.tieo.arbay.judging

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.chat.AuctionBids
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Reads the sold list of one product after another for the price model, from `sold_sweep.txt`, one
 * `search words | product type` per line, and then the auctions bid on lately, so what they ended
 * at is on record.
 *
 * A read goes through [SoldPriceReader]'s single turn in the shared browser and the sweep leaves
 * minutes between reads, so it never competes with a watch for long; like a watch it waits while a
 * bid is due. The position is kept on disk, so a restart goes on where it was.
 */
object SoldSweep {
    private val log = LoggerFactory.getLogger(SoldSweep::class.java)
    private val list = DataDir.file("sold_sweep.txt")
    private val position = DataDir.file("sold_sweep_position.txt")
    private val GAP = 10.minutes

    /** A market that refuses one read refuses the next: the same product is tried again after this. */
    private val AFTER_FAILURE = 1.hours
    private val BID_QUIET = 45.minutes
    private val BIDS_BACK = 14.days

    internal fun parse(lines: List<String>): List<Product> = lines.mapNotNull { line ->
        val words = line.substringBefore('|').trim()
        if (words.isEmpty() || words.startsWith("#")) null else Product(words, line.substringAfter('|', "").trim().ifEmpty { null })
    }

    internal data class Product(val words: String, val type: String?)

    fun start() {
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (true) {
                delay(GAP)
                while (AuctionBids.busySoon(BID_QUIET)) delay(1.minutes)
                try {
                    next()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("sold sweep: {}", e.message)
                    delay(AFTER_FAILURE)
                }
            }
        }
    }

    private fun products(): List<Product> {
        val listed = if (list.exists()) parse(list.readLines()) else emptyList()
        val now = Clock.System.now()
        val bid = AuctionBids.all().filter { it.endsAt < now && now - it.endsAt < BIDS_BACK }.map { Product(it.title, "own bid") }
        return (listed + bid).distinctBy { it.words.lowercase() }
    }

    private suspend fun next() {
        val all = products()
        if (all.isEmpty()) return
        val at = (position.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0).let { if (it in all.indices) it else 0 }
        val product = all[at]
        SoldPriceReader.of(SoldPriceReader.Ask(words = product.words, type = product.type))
        position.writeText("${at + 1}")
        log.info("sold sweep {}/{}: {}", at + 1, all.size, product.words)
    }
}
