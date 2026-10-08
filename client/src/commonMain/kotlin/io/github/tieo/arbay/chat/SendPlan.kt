package io.github.tieo.arbay.chat

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SendRequest
import io.github.tieo.arbay.model.buyerProtection
import io.github.tieo.arbay.model.canMessage
import io.github.tieo.arbay.model.fillIn
import io.github.tieo.arbay.model.offerWithin
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
 * The price is [allInEur] minus what else the buyer pays on that listing: shipping as the ad states
 * it, and the market's protection fee where the item is shipped. An ad that is pickup only is paid
 * in person, so nothing comes off. An ad that ships without saying for how much gets the price
 * before shipping and says so, rather than a guessed shipping cost.
 */
fun planSend(listings: List<Listing>, allInEur: Double?, template: String, edits: Map<String, String>, blockIds: List<String> = emptyList()): List<SendLine> =
    listings.map { listing ->
        val title = listing.title.tidyTitle()
        if (!listing.platformId.canMessage) {
            return@map SendLine(listing, null, null, "", edited = false, unreachable = "Only Kleinanzeigen sellers can be written to from Arbay")
        }
        val shipping = listing.shipping
        val pickupOnly = shipping != null && !shipping.available && shipping.pickup
        val shippingEur = when {
            shipping == null || pickupOnly -> 0.0
            shipping.free -> 0.0
            else -> shipping.cost?.takeIf { it.currency == Currency.EUR }?.amount?.div(100.0)
        }
        val price = allInEur?.let { offerWithin(it, shippingEur ?: 0.0, if (pickupOnly) null else listing.platformId.buyerProtection) }
        val note = when {
            allInEur == null -> null
            pickupOnly -> "pickup, paid in person"
            shippingEur == null -> "before shipping, the ad gives no cost"
            shipping == null -> "the ad says nothing about shipping"
            else -> null
        }
        val shippingText = when {
            shipping == null -> null
            shipping.free -> "kostenlos"
            shippingEur != null && shippingEur > 0 -> formatEuro(shippingEur)
            else -> null
        }
        val own = edits[listing.id]
        SendLine(listing, price, note, own ?: fillIn(template, price, title, shippingText), edited = own != null, blockIds = blockIds)
    }

/** The lines that can go, as the server takes them. */
fun List<SendLine>.toRequest(searchId: String?, allInEur: Double?): SendRequest = SendRequest(
    messages = filter { it.unreachable == null && it.text.isNotBlank() }
        .map { SendRequest.Draft(it.listing.id, it.listing.title.tidyTitle(), it.text, it.blockIds, it.price) },
    searchId = searchId,
    allInEur = allInEur,
)

/** Whether a line still has a fill-in nobody could complete, which would reach the seller as "{preis}". */
val SendLine.hasOpenFillIn: Boolean get() = Regex("""\{(preis|titel|versand)}""").containsMatchIn(text)

private fun formatEuro(eur: Double): String {
    val cents = kotlin.math.round(eur * 100).toLong()
    return "${cents / 100},${(cents % 100).toString().padStart(2, '0')} €"
}
