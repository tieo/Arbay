package io.github.tieo.arbay.chat

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.AuctionBid
import io.github.tieo.arbay.model.AuctionBidRequest
import io.github.tieo.arbay.model.AuctionBidState
import io.github.tieo.arbay.model.BidReview
import io.github.tieo.arbay.neuland.NeuAuth
import io.github.tieo.arbay.neuland.Neuland
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

/**
 * Bids on eBay.de auctions, placed by Neuland in the signed-in account browser in each auction's
 * last seconds: a bid placed early only gives other bidders time to outbid it and drives the price
 * up. One bid stands at a time, so two auctions can never both be won: a bid whose turn comes while
 * another is placed and its auction not ended is skipped, and Neuland refuses one beside any bid it
 * has not settled. Every limit is checked again where the bid is placed: Neuland holds its own most
 * a bid may cost and confirms only while eBay's review reads back the bid as asked and totals no
 * more than the user's all-in limit.
 */
object AuctionBids {
    private val log = LoggerFactory.getLogger(AuctionBids::class.java)
    private val file = DataDir.file("auction_bids.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val neulandJson = Json { ignoreUnknownKeys = true }

    /** How long before an auction's end its bid is opened on eBay: the item page, a fresh sign-in
     *  where eBay asks one, the bid form and its review. */
    val OPEN_BEFORE: Duration = 2.minutes
    /** How long before the end the review is confirmed: late enough to leave no time to answer it,
     *  early enough for eBay to take it. */
    val CONFIRM_BEFORE: Duration = 6.seconds

    private val bids = mutableListOf<AuctionBid>()

    /** The most any bid may cost with shipping, in euros (`ARBAY_MOST_BID_EUR`): Arbay's own limit,
     *  beside Neuland's, so a limit asked in the wrong unit (cents as euros) is refused here already.
     *  Without it no bid is set up. */
    internal var mostBidEur: Double? = System.getenv("ARBAY_MOST_BID_EUR")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }

    init {
        file.readStore(log) { json.decodeFromString<List<AuctionBid>>(it) }?.let { bids += it }
    }

    fun all(): List<AuctionBid> = synchronized(bids) { bids.sortedBy { it.endsAt } }

    /** Forgets every bid, for a test that starts from none. */
    internal fun forgetAll() = synchronized(bids) {
        bids.clear()
        persist()
    }

    /**
     * Sets a bid up for [request]'s auction, placed in its last seconds up to the all-in limit less
     * its shipping. Refused for anything but an eBay.de auction still running for longer than its
     * bid takes to open, and for an auction already asked for.
     */
    fun ask(request: AuctionBidRequest, now: Instant = Clock.System.now()): AuctionBid {
        val most = mostBidEur ?: throw IllegalArgumentException("Arbay sets up no bid without the most a bid may cost (ARBAY_MOST_BID_EUR)")
        require(request.allInEur.isFinite() && request.allInEur <= most) { "an all-in limit of ${request.allInEur} € passes the $most € a bid may cost" }
        val maxCents = maxCents(request.allInEur, request.shippingEur)
            ?: throw IllegalArgumentException("an all-in limit of ${request.allInEur} € leaves nothing to bid after ${request.shippingEur} € shipping")
        require(itemOf(request.url) != null) { "${request.url} is no eBay.de item" }
        require(request.endsAt > now + OPEN_BEFORE + 30.seconds) { "the auction ends at ${request.endsAt}, too soon to bid" }
        val bid = AuctionBid(
            listingId = request.listingId,
            url = "https://www.ebay.de/itm/${itemOf(request.url)}",
            title = request.title,
            endsAt = request.endsAt,
            allInEur = request.allInEur,
            maxEur = maxCents / 100.0,
            shippingEur = request.shippingEur,
            asked = now,
        )
        synchronized(bids) {
            require(bids.none { it.listingId == bid.listingId && it.state in OPEN }) { "a bid on ${bid.listingId} is already set up" }
            bids += bid
            persist()
        }
        log.info("Bid set up on {} up to {} € ({} € all in), ending {}", bid.url, bid.maxEur, bid.allInEur, bid.endsAt)
        return bid
    }

    /** Takes back a bid still waiting; one being placed or placed stands. */
    fun cancel(listingId: String): Boolean = synchronized(bids) {
        val at = bids.indexOfFirst { it.listingId == listingId && it.state == AuctionBidState.WAITING }
        if (at < 0) return false
        bids[at] = bids[at].copy(state = AuctionBidState.CANCELLED)
        persist()
        true
    }

    /** eBay's review of a bid on [url] up to [maxEur] within [allInEur], read without placing it.
     *  Refused while a bid is being placed or is about to be: a look holds the account browser,
     *  and would hold the bid past its auction's end. */
    suspend fun review(url: String, maxEur: Double, allInEur: Double, now: Instant = Clock.System.now()): BidReview {
        synchronized(bids) {
            require(bids.none { it.state == AuctionBidState.PLACING || (it.state == AuctionBidState.WAITING && it.endsAt - OPEN_BEFORE - 10.minutes <= now) }) {
                "a bid is being placed or about to be; look again after it"
            }
        }
        return neuland(url, maxEur, allInEur, place = false, at = null)
    }

    /** Places the bids whose time has come; called every few seconds. */
    suspend fun tick(now: Instant = Clock.System.now()) {
        val due = synchronized(bids) {
            for ((i, b) in bids.withIndex()) {
                when {
                    b.state == AuctionBidState.WAITING && b.endsAt - CONFIRM_BEFORE - 20.seconds < now ->
                        bids[i] = b.copy(state = AuctionBidState.SKIPPED, problem = "its turn came too late to open the bid")
                    b.state == AuctionBidState.PLACED && b.endsAt + 1.minutes < now -> bids[i] = b.copy(state = AuctionBidState.ENDED)
                }
            }
            val next = bids.filter { it.state == AuctionBidState.WAITING && it.endsAt - OPEN_BEFORE <= now }.minByOrNull { it.endsAt }
            if (next == null) {
                persist()
                return
            }
            // An ended auction may have been won: its bid blocks the next until it is settled.
            val standing = bids.firstOrNull { it.state == AuctionBidState.PLACING || it.state == AuctionBidState.PLACED || it.state == AuctionBidState.ENDED }
            val at = bids.indexOf(next)
            if (standing != null) {
                bids[at] = next.copy(state = AuctionBidState.SKIPPED, problem = "the bid on ${standing.url} stood when its turn came")
                persist()
                return
            }
            bids[at] = next.copy(state = AuctionBidState.PLACING)
            persist()
            bids[at]
        }
        val placed = try {
            neuland(due.url, due.maxEur, due.allInEur, place = true, at = due.endsAt - CONFIRM_BEFORE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Only Neuland's own refusal before anything happened on eBay is a bid not placed; any
            // other failure (no answer, a timeout on eBay's page, an answer that does not read) may
            // have left a bid standing, and counts as placed.
            val declined = (e as? Neuland.Refusal)?.declined == true
            BidReview(biddable = true, placed = !declined, problem = (if (declined) "" else "may stand: ") + e.message)
        }
        synchronized(bids) {
            val i = bids.indexOfFirst { it.listingId == due.listingId && it.state == AuctionBidState.PLACING }
            if (i >= 0) {
                bids[i] = if (placed.placed) {
                    bids[i].copy(state = AuctionBidState.PLACED, placedAt = Clock.System.now(), outcome = placed.outcome, problem = placed.problem)
                } else {
                    bids[i].copy(state = AuctionBidState.NOT_PLACED, problem = placed.problem ?: "eBay took no bid")
                }
                persist()
            }
        }
        log.info("Bid on {} up to {} €: placed {}, {}", due.url, due.maxEur, placed.placed, placed.problem ?: placed.outcome?.take(200))
    }

    /** Ticks for as long as the server runs. */
    suspend fun run() {
        // A bid left PLACING by a stop in the middle may stand: it counts as placed.
        synchronized(bids) {
            for ((i, b) in bids.withIndex()) if (b.state == AuctionBidState.PLACING) bids[i] = b.copy(state = AuctionBidState.PLACED, problem = "Arbay stopped while it was being placed")
            persist()
        }
        while (true) {
            try {
                tick()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Placing bids failed: {}", e.message)
            }
            delay(5_000)
        }
    }

    private suspend fun neuland(url: String, maxEur: Double, allInEur: Double, place: Boolean, at: Instant?): BidReview {
        val body = buildJsonObject {
            put("url", JsonPrimitive(url))
            put("maxCents", JsonPrimitive((maxEur * 100).roundToLong()))
            put("mostCents", JsonPrimitive((allInEur * 100).roundToLong()))
            put("place", JsonPrimitive(place))
            at?.let { put("at", JsonPrimitive(it.toString())) }
        }
        val answer = Neuland.request(io.ktor.http.HttpMethod.Post, "browsers/${NeuAuth.BROWSER}/bid/ebay.de", body, limitS = 900)
        val read = neulandJson.decodeFromJsonElement(NeulandBid.serializer(), answer)
        return BidReview(
            biddable = read.biddable,
            currentEur = read.current?.eur(),
            bids = read.bids,
            leastEur = read.least?.eur(),
            maxEur = read.max?.eur(),
            shippingEur = read.shipping?.eur(),
            totalEur = read.total?.eur(),
            placed = read.placed,
            standing = read.standing,
            outcome = read.outcome,
            problem = read.problem,
        )
    }

    private val OPEN = setOf(AuctionBidState.WAITING, AuctionBidState.PLACING, AuctionBidState.PLACED)

    /** The most a bid goes to for [allInEur] in all with [shippingEur] shipping, in cents; none where
     *  nothing is left. */
    internal fun maxCents(allInEur: Double, shippingEur: Double): Long? {
        if (!allInEur.isFinite() || !shippingEur.isFinite() || shippingEur < 0) return null
        val left = (allInEur * 100).roundToLong() - (shippingEur * 100).roundToLong()
        return left.takeIf { it > 0 }
    }

    /** The item an eBay.de address names. */
    internal fun itemOf(url: String): String? =
        url.removePrefix("https://www.ebay.de/itm/").takeIf { it != url }?.substringBefore('?')?.substringBefore('/')?.takeIf { it.length >= 6 && it.all(Char::isDigit) }

    private fun persist() {
        runCatching { file.writeTextAtomically(json.encodeToString(bids.toList())) }
            .onFailure { log.error("Could not save auction bids: {}", it.message) }
    }

    @Serializable
    private data class Amount(val cents: Long, val currency: String = "EUR") {
        fun eur(): Double = cents / 100.0
    }

    @Serializable
    private data class NeulandBid(
        val biddable: Boolean = false,
        val current: Amount? = null,
        val bids: Int? = null,
        val least: Amount? = null,
        val max: Amount? = null,
        val shipping: Amount? = null,
        val total: Amount? = null,
        val placed: Boolean = false,
        val standing: Boolean? = null,
        val outcome: String? = null,
        val problem: String? = null,
    )
}
