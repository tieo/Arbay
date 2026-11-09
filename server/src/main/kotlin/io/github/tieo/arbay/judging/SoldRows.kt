package io.github.tieo.arbay.judging

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SellerType
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Every sale a look at a sold list has shown, one line each in a file that only grows, with what the
 * list says of it: what a price model is fitted on, where [SoldPriceReader] itself keeps only
 * quartiles. A field the list does not show is left out of the line.
 */
class SoldRows(private val file: File) {
    @Serializable
    data class Row(
        val readAt: Instant,
        val asked: String,
        val type: String? = null,
        val offer: String,
        val url: String,
        val title: String,
        val priceCents: Long,
        /** The price with shipping, in cents of [currency]. */
        val landedCents: Long,
        val currency: Currency,
        val saleType: SaleType? = null,
        val condition: Condition? = null,
        val soldAt: Instant? = null,
        /** Sold to a proposal below the price, whose amount the market hides. */
        val soldToOffer: Boolean = false,
        val bids: Int? = null,
        val sellerType: SellerType? = null,
        val sellerName: String? = null,
        val sellerRating: Double? = null,
        val sellerRatings: Int? = null,
    )

    private val log = LoggerFactory.getLogger(SoldRows::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val known = HashSet<String>()

    init {
        if (file.exists()) file.forEachLine { line ->
            runCatching { json.decodeFromString(Row.serializer(), line) }.getOrNull()?.let { known += it.offer }
        }
    }

    /** Writes the sales among [offers] that no earlier look has shown. */
    @Synchronized
    fun add(asked: String, type: String?, offers: Collection<Listing>, now: Instant = Clock.System.now()) {
        val rows = offers.filter { it.sold && it.id !in known }.distinctBy { it.id }.map { l ->
            Row(
                readAt = now, asked = asked, type = type, offer = l.id, url = l.url, title = l.title,
                priceCents = l.price.amount, landedCents = l.effectivePrice.amount, currency = l.price.currency,
                saleType = l.saleType, condition = l.condition, soldAt = l.soldDate, soldToOffer = l.soldToOffer, bids = l.bidCount,
                sellerType = l.seller?.type, sellerName = l.seller?.name, sellerRating = l.seller?.rating, sellerRatings = l.seller?.reviewCount,
            )
        }
        if (rows.isEmpty()) return
        runCatching { file.appendText(rows.joinToString("") { json.encodeToString(Row.serializer(), it) + "\n" }) }
            .onSuccess { known += rows.map { it.offer } }
            .onFailure { log.error("could not write the sold rows: {}", it.message) }
    }

    companion object {
        val live by lazy { SoldRows(DataDir.file("sold_rows.jsonl")) }
    }
}
