package io.github.tieo.arbay.model

import kotlin.math.floor
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Talking to sellers through the user's own market account.
 *
 * Every word sent is the user's: Arbay fills the fill-ins of a text the user wrote and sends it when
 * the user says so, at a person's pace. Only Kleinanzeigen can be talked to; its message box is the
 * one a signed-in browser can reach.
 */
@Serializable
data class ChatAccount(
    val market: PlatformId = PlatformId.KLEINANZEIGEN,
    val signedIn: Boolean = false,
    val name: String? = null,
    /** Why the account can't be reached right now, when it can't. */
    val problem: String? = null,
    /** The site NeuAuth signs this market's account in to ("kleinanzeigen.de"), where it does:
     *  its sign-in is asked and shown as the shop accounts' are. */
    val signInSite: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val market: PlatformId = PlatformId.KLEINANZEIGEN,
    /** The Arbay listing id of the ad this is about, as the results know it. */
    val listingId: String? = null,
    val adTitle: String? = null,
    val adImage: String? = null,
    val partner: String? = null,
    /** Whether the user is the one buying here; conversations about the user's own ads say false. */
    val buying: Boolean = true,
    val unread: Int = 0,
    val lastAt: Instant? = null,
    val lastText: String? = null,
    /** The whole exchange, oldest first; empty in a list of conversations. */
    val messages: List<ChatMessage> = emptyList(),
)

@Serializable
data class ChatMessage(
    val id: String,
    val mine: Boolean,
    val text: String,
    val at: Instant? = null,
    val attachments: List<String> = emptyList(),
    /** A price proposal made through the market's own offer button, in euro. */
    val offerEur: Double? = null,
)

/** One message on its way to a seller, held until its moment so several don't land at once. */
@Serializable
data class OutgoingMessage(
    val id: String,
    val listingId: String,
    val title: String,
    val text: String,
    val sendAt: Instant,
    val state: OutgoingState = OutgoingState.WAITING,
    val conversationId: String? = null,
    val error: String? = null,
    /** Which of the user's text blocks the message was made of, for seeing later how each does. */
    val blockIds: List<String> = emptyList(),
    /** The price the message offered, when it offered one. */
    val price: Int? = null,
)

@Serializable
enum class OutgoingState { WAITING, SENDING, SENT, FAILED, CANCELLED }

/** Messages the user wants sent, one per listing, already filled in and read over. */
@Serializable
data class SendRequest(
    val messages: List<Draft>,
    /** The saved search these came from and the all-in limit used, remembered as its default. */
    val searchId: String? = null,
    val allInEur: Double? = null,
) {
    @Serializable
    data class Draft(
        val listingId: String,
        val title: String,
        val text: String,
        val blockIds: List<String> = emptyList(),
        val price: Int? = null,
    )
}

/**
 * A block of text the user wrote, with fill-ins Arbay completes per listing. A message is one or
 * more blocks in the order picked (a greeting, the offer, the Käuferschutz paragraph), so each block
 * can be judged and changed on its own.
 */
@Serializable
data class MessageTemplate(val id: String, val name: String, val text: String)

/** [blocks] as one message, in the order given. */
fun composeBlocks(blocks: List<MessageTemplate>): String = blocks.joinToString("\n\n") { it.text.trim() }

/** How sellers answered the messages one text block went out in. */
@Serializable
data class BlockReview(
    val blockId: String,
    val name: String,
    val sent: Int,
    val answered: Int,
    /** The middle time sellers took to answer, in minutes, over those who did. */
    val middleMinutesToAnswer: Long? = null,
    val answers: List<ReviewAnswer> = emptyList(),
)

/** One message sent and what came back to it first. */
@Serializable
data class ReviewAnswer(
    val conversationId: String,
    val title: String,
    val sentAt: Instant,
    val price: Int? = null,
    val answer: String? = null,
    val answeredAt: Instant? = null,
)

/** The user's texts, and the all-in limit last used per saved search. */
@Serializable
data class ChatSettings(
    val templates: List<MessageTemplate> = emptyList(),
    val allInBySearch: Map<String, Double> = emptyMap(),
    /** Delivery to the user's own address rather than to a parcel shop. */
    val toDoor: Boolean = true,
    /** Where a shop's checkout delivers to a pickup point, the point the user picks up from. */
    val pickup: PickupPoint? = null,
    /** The user's own address and phone, for a shop's invoice. */
    val buyer: BuyerAddress? = null,
)

/**
 * A pickup point a shop delivers to: [near] is what the shop's map of points is searched for,
 * [address] the point's address as shops list it, [kind] what the shop calls it ("Packstation"),
 * and [postnummer] the DHL customer number a Packstation delivers to.
 */
@Serializable
data class PickupPoint(val near: String, val address: String, val kind: String, val postnummer: String? = null)

/** The user's own address and phone, as an invoice carries them; [phone] as dialled at home. */
@Serializable
data class BuyerAddress(
    val name: String,
    val street: String,
    val zip: String,
    val city: String,
    val phone: String,
    /** Where a shop sends its order confirmation; a web shop's guest checkout asks for it. */
    val email: String? = null,
)

/**
 * What delivery costs the buyer, given the [shipping] an ad states; null when it ships without
 * saying for how much. Pickup only and free shipping cost nothing, and an ad that says nothing
 * about shipping is taken at its price alone. To the door, a parcel shop price counts as the
 * door delivery the market's price list puts beside it ([Shipping.doorCost]).
 */
fun shippingCostEur(shipping: Shipping?, toDoor: Boolean): Double? {
    shipping ?: return 0.0
    if (shipping.free || (!shipping.available && shipping.pickup)) return 0.0
    val cost = (if (toDoor) shipping.doorCost ?: shipping.cost else shipping.cost) ?: return null
    return cost.takeIf { it.currency == Currency.EUR }?.amount?.div(100.0)
}

/** The door delivery [shippingCostEur] counts instead of the ad's parcel shop price, in words; null when none. */
fun doorDeliveryUsed(shipping: Shipping?, toDoor: Boolean): String? {
    if (!toDoor || shipping == null || shipping.free || (!shipping.available && shipping.pickup)) return null
    val door = shipping.doorCost ?: return null
    return "${shipping.doorBy ?: "delivery"} to the door, ${formatEuroCents(door.amount)} instead of ${shipping.cost?.let { formatEuroCents(it.amount) } ?: "?"} to a parcel shop"
}

private fun formatEuroCents(cents: Long): String {
    val whole = kotlin.math.abs(cents)
    return "${if (cents < 0) "-" else ""}${whole / 100},${(whole % 100).toString().padStart(2, '0')} €"
}

/** An amount in euro as a German price reads: "193,39 €". */
fun euroText(eur: Double): String = formatEuroCents(kotlin.math.round(eur * 100).toLong())

/**
 * What to offer a seller so that everything the buyer pays stays within [allInEur].
 *
 * Bought through Kleinanzeigen's "Sicher bezahlen", the buyer pays the price, the shipping and a
 * service fee of 0.50 € plus 4.5 % of the price (hilfe.kleinanzeigen.de, "Was kostet Sicher
 * bezahlen"), so the price is what is left after shipping and the fixed part, divided by 1.045.
 * Rounded down to whole euro: an offer is a number a person says, and down keeps it within the limit.
 */
fun offerWithin(allInEur: Double, shippingEur: Double, protection: BuyerProtection?): Int {
    val left = allInEur - shippingEur
    val price = if (protection == null) left else (left - protection.fixedEur) / (1 + protection.share)
    return floor(price).toInt().coerceAtLeast(0)
}

/** One way a seller ships, as the "Sicher bezahlen" dialog names and prices it. */
@Serializable
data class ShippingOption(val name: String, val eur: Double)

/**
 * A price offer through Kleinanzeigen's "Sicher bezahlen", as its own dialog worked it out: the
 * [price] offered, the buyer protection [fee], the shipping the buyer picks at payment
 * ([shippingEur], out of [shipping]) and what the buyer [paid] in all. [sent] once it shows in the
 * conversation; [picture] is the dialog as it looked, a JPEG in base64.
 */
@Serializable
data class PriceOffer(
    val price: Double,
    val fee: Double? = null,
    val shippingEur: Double? = null,
    val shipping: List<ShippingOption> = emptyList(),
    val paid: Double? = null,
    val sent: Boolean = false,
    val picture: String? = null,
)

/**
 * Buying an item outright at a market's checkout (Vinted's): [allInEur] is the most the whole
 * payment may come to, and only with [pay] is anything paid. [together] are further items at the
 * same web shop, one of each, bought in the same order so its shipping is paid once.
 */
@Serializable
data class PurchaseRequest(
    val url: String,
    val allInEur: Double,
    val pay: Boolean = false,
    val together: List<String> = emptyList(),
    /** How a web shop's checkout pays: "card" for the user's card, else PayPal. */
    val payment: String? = null,
)

/**
 * A checkout as Arbay read it: the item's price, the market's buyer protection [feeEur], the
 * shipping and the [totalEur] it adds up to, the saved [payment] method, and whether it was [paid].
 * [buyable] is false where the item page no longer offers to buy it. [problem] says why a payment
 * stopped, and [picture] is the page it stopped on, a JPEG in base64.
 */
@Serializable
data class Purchase(
    val buyable: Boolean,
    val itemEur: Double? = null,
    val feeEur: Double? = null,
    val shippingEur: Double? = null,
    val totalEur: Double? = null,
    val payment: String? = null,
    val paid: Boolean = false,
    val problem: String? = null,
    val picture: String? = null,
    /** The delivery the checkout chose, in the shop's words ("DHL-Box - Packstation"). */
    val delivery: String? = null,
    /** The order's number, as the shop's confirmation states it. */
    val order: String? = null,
    /** Whether the shop's order button was pressed: the order may stand, paid or not. */
    val placed: Boolean = false,
) {
    /** What the shop took off the lines to reach the total, such as a coupon's amount; null where nothing was or a line is unread. */
    val discountEur: Double?
        get() {
            val cents = kotlin.math.round((itemEur ?: return null) * 100 + (feeEur ?: 0.0) * 100 + (shippingEur ?: return null) * 100 - (totalEur ?: return null) * 100).toLong()
            return if (cents > 0) cents / 100.0 else null
        }
}

/**
 * The shop whose own checkout Arbay buys an offer at [url] through, as Neuland names it: Vinted's,
 * eBay.de's for its item pages, and alza's for its product pages. Null where the address alone
 * names none; a web shop's checkout is found by the server ([Listing.checkout]).
 */
fun checkoutSite(url: String): String? {
    val rest = url.removePrefix("https://").takeIf { it != url } ?: return null
    val host = rest.substringBefore('/').substringBefore('?')
    val path = "/" + rest.substringAfter('/', "").substringBefore('?')
    return when {
        host == "www.vinted.de" && path.startsWith("/items/") -> "vinted.de"
        host == "www.ebay.de" && Regex("^/itm/[0-9]+$").matches(path) -> "ebay.de"
        host == "www.alza.de" && Regex("^/[^/]+-d[0-9]+\\.htm$").matches(path) -> "alza.de"
        else -> null
    }
}

/**
 * The shop whose own checkout buys [listing] outright: one its address names, else the web shop
 * the server found a checkout at ([Listing.checkout]). An eBay address is the same whether it sells
 * or auctions, so eBay's checkout takes only an offer known to be at a fixed price: an auction is
 * bid on, and what is hired out is not for sale.
 */
fun checkoutSite(listing: Listing): String? = (checkoutSite(listing.url) ?: listing.checkout)?.takeIf { site ->
    if (site == "ebay.de") listing.saleType == SaleType.FIXED_PRICE
    else listing.saleType != SaleType.AUCTION && listing.saleType != SaleType.RENT
}

/**
 * The offers among [offers] that can go into one order with [listing]: others at the same web shop,
 * each address once. A market's own checkout buys one item per order, so it has none.
 */
fun sameOrderOffers(listing: Listing, offers: List<Listing>): List<Listing> {
    val site = checkoutSite(listing) ?: return emptyList()
    if (checkoutSite(listing.url) != null) return emptyList()
    val own = productKey(listing.url)
    return offers.filter { it.id != listing.id && productKey(it.url) != own && checkoutSite(it) == site && it.price.currency == Currency.EUR }
        .distinctBy { productKey(it.url) }
}

/**
 * A shop product's address without what only picks a view of it: host and path, lower case. A
 * Shopify product is listed both bare and with `?variant=`, which is the same roller twice, so one
 * order takes one address per product.
 */
fun productKey(url: String): String {
    val rest = url.substringAfter("://").substringBefore('?').substringBefore('#').trimEnd('/')
    return rest.lowercase().removePrefix("www.")
}

/** A shop [checkoutSite] names, as the user knows it. */
fun checkoutShop(site: String): String = when (site) {
    "vinted.de" -> "Vinted"
    "ebay.de" -> "eBay"
    "alza.de" -> "alza"
    else -> site
}

/** A market's buyer protection, as the buyer pays for it: a fixed part and a share of the price. */
@Serializable
data class BuyerProtection(val fixedEur: Double, val share: Double)

/** What buying through Kleinanzeigen costs on top of the price, as the server last read it from Kleinanzeigen. */
@Serializable
data class ChatCosts(
    /** The "Sicher bezahlen" fee; null when it could not be read. */
    val protection: BuyerProtection? = null,
    val protectionReadAt: Instant? = null,
)

/** One seller's offer: the price to name, the same total paid directly, and what to tell the user about it. */
data class Offer(val price: Int?, val direct: Int?, val shippingEur: Double?, val note: String?)

/**
 * What to offer for a listing so that all the buyer pays stays within [allInEur]: shipping as
 * [shippingCostEur] prices it and the market's protection fee from [costs] where the item is
 * shipped. Never more than the seller asks ([askingEur]). The direct total is capped too: the
 * price the seller would get for the phone itself, the direct total less shipping, stays at or
 * below the ask. Without a readable fee no price is named.
 */
fun offerFor(platform: PlatformId, askingEur: Int?, shipping: Shipping?, allInEur: Double?, toDoor: Boolean, costs: ChatCosts?): Offer {
    val pickupOnly = shipping != null && !shipping.available && shipping.pickup
    val shippingEur = shippingCostEur(shipping, toDoor)
    val protected = !pickupOnly && platform.canMessage
    val protection = if (protected) costs?.protection else null
    if (allInEur != null && protected && protection == null) {
        return Offer(null, null, shippingEur, "the Sicher bezahlen fee could not be read from Kleinanzeigen")
    }
    val asking = askingEur?.takeIf { it > 0 }
    val price = allInEur?.let { offerWithin(it, shippingEur ?: 0.0, protection) }
        ?.let { if (asking != null) minOf(it, asking) else it }
    // Paying directly costs nothing on top, so the whole all-in limit goes to the seller. Worked
    // back from [price] instead, the euro that price was rounded down by was lost: 449 offered
    // against a 450 limit. The ad's price is the ceiling of every line, shipping included: the
    // user never offers a seller more than they asked for, and a PayPal total of ask plus
    // shipping is more.
    val direct = allInEur?.let { floor(it).toInt() }
        ?.let { if (asking != null) minOf(it, asking) else it }
    val note = when {
        allInEur == null -> null
        pickupOnly -> "pickup, paid in person"
        shippingEur == null -> "before shipping, the ad gives no cost"
        shipping == null -> "the ad says nothing about shipping"
        else -> doorDeliveryUsed(shipping, toDoor)
    }
    return Offer(price, direct, shippingEur, note)
}

/** The name of the user's account on this market: one eBay account answers for every eBay site. */
val PlatformId.accountName: String get() = if (name.startsWith("EBAY")) "eBay" else displayName

/** The markets whose sellers Arbay can write to. */
val PlatformId.canMessage: Boolean get() = this == PlatformId.KLEINANZEIGEN

/** The fill-ins a text can use, and what each stands for. */
val TEMPLATE_FILL_INS: List<Pair<String, String>> = listOf(
    "{preis}" to "the price to offer",
    "{preis_direkt}" to "the whole all-in limit paid directly, without buyer protection, shipping included, all to the seller; never above the ask",
    "{preis+20}" to "the price to offer plus 20 € (any amount)",
    "{titel}" to "the ad's title",
    "{versand}" to "shipping as the ad states it",
)

/** [text] with its fill-ins completed for one listing. */
fun fillIn(text: String, price: Int?, title: String, shipping: String?, directPrice: Int? = null): String =
    text.replace("{preis_direkt}", directPrice?.toString() ?: "{preis_direkt}")
        .replace(Regex("""\{preis\+(\d+)\}""")) { m -> price?.let { (it + m.groupValues[1].toInt()).toString() } ?: m.value }
        .replace("{preis}", price?.toString() ?: "{preis}")
        .replace("{titel}", title)
        .replace("{versand}", shipping ?: "{versand}")

/**
 * A sign-in an address asks the app to open: Settings with [market]'s sign-in page, or with
 * [payment] the page of the payment service its checkout uses, under `/settings?signin=VINTED_DE&payment=true`.
 * A push sends it when an account Arbay needs signed the user out, so a tap opens the field to type in.
 */
data class SignInLink(val market: PlatformId, val payment: Boolean = false) {
    fun query(): String = "signin=${market.name}" + if (payment) "&payment=true" else ""

    /** The address the phone app opens, its scheme registered by the app. */
    fun appLink(): String = "$APP_SCHEME://open/settings?${query()}"

    companion object {
        const val APP_SCHEME = "arbay"

        /** The sign-in [query] (an address's part after "?") asks for; null where it asks none. */
        fun parse(query: String?): SignInLink? {
            val params = query.orEmpty().removePrefix("?").split('&').mapNotNull {
                it.split('=', limit = 2).takeIf { kv -> kv.size == 2 }?.let { (k, v) -> k to v }
            }.toMap()
            val market = params["signin"]?.let { name -> PlatformId.entries.firstOrNull { it.name == name } } ?: return null
            return SignInLink(market, params["payment"] == "true")
        }
    }
}
