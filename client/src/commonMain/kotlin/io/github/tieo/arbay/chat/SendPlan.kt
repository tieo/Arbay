package io.github.tieo.arbay.chat

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SendRequest
import io.github.tieo.arbay.model.Shipping
import io.github.tieo.arbay.model.canMessage
import io.github.tieo.arbay.model.fillIn
import io.github.tieo.arbay.model.offerFor
import io.github.tieo.arbay.model.ChatCosts
import io.github.tieo.arbay.model.tidyTitle

/** One seller in the send window: what they will be offered and read. */
data class SendLine(
    val listing: Listing,
    /** The price to offer; null where there is no limit to work it out from. */
    val price: Int?,
    /** What the price leaves out or assumes, when it does. */
    val note: String?,
    val text: String,
    /** The user changed this seller's message by hand, so the shared text no longer fills it. */
    val edited: Boolean,
    /** Why this seller can't be written to, when they can't. */
    val unreachable: String? = null,
    /** The text blocks the message was made of. */
    val blockIds: List<String> = emptyList(),
)

/**
 * Each selected listing with its own price and message.
 *
 * The price is [allInEur] minus what else the buyer pays on that listing, as [offerFor] works it out.
 */
fun planSend(
    listings: List<Listing>,
    allInEur: Double?,
    template: String,
    edits: Map<String, String>,
    blockIds: List<String> = emptyList(),
    /** Shipping as each ad's own page states it, which beats the card's "ships". */
    pageShipping: Map<String, Shipping> = emptyMap(),
    toDoor: Boolean = true,
    /** The fee as the server last read it from the market. */
    costs: ChatCosts? = null,
): List<SendLine> =
    listings.map { listing ->
        val title = listing.title.tidyTitle()
        if (!listing.platformId.canMessage) {
            return@map SendLine(listing, null, null, "", edited = false, unreachable = "Only Kleinanzeigen sellers can be written to from Arbay")
        }
        val shipping = pageShipping[listing.id] ?: listing.shipping
        val asking = listing.price.takeIf { it.currency == Currency.EUR }?.amount?.div(100)?.toInt()
        val offer = offerFor(listing.platformId, asking, shipping, allInEur, toDoor, costs)
        val shippingText = when {
            shipping?.free == true -> "kostenlos"
            else -> offer.shippingEur?.takeIf { it > 0 }?.let { formatEuro(it) }
        }
        val own = edits[listing.id]
        SendLine(listing, offer.price, offer.note, own ?: fillIn(template, offer.price, title, shippingText, offer.direct), edited = own != null, blockIds = blockIds)
    }

/** The lines that can go, as the server takes them. */
fun List<SendLine>.toRequest(searchId: String?, allInEur: Double?): SendRequest = SendRequest(
    messages = filter { it.unreachable == null && it.text.isNotBlank() }
        .map { SendRequest.Draft(it.listing.id, it.listing.title.tidyTitle(), it.text, it.blockIds, it.price) },
    searchId = searchId,
    allInEur = allInEur,
)

/** Whether a line still has a fill-in nobody could complete, which would reach the seller as "{preis}". */
val SendLine.hasOpenFillIn: Boolean get() = Regex("""\{(preis(\+\d+|_direkt)?|titel|versand)}""").containsMatchIn(text)

private fun formatEuro(eur: Double): String {
    val cents = kotlin.math.round(eur * 100).toLong()
    return "${cents / 100},${(cents % 100).toString().padStart(2, '0')} €"
}
