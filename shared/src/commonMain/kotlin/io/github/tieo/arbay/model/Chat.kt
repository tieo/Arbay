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
    /** The market's sign-in page is open in Arbay's browser, waiting for the user. */
    val signingIn: Boolean = false,
    /** Why the account can't be reached right now, when it can't. */
    val problem: String? = null,
)

/**
 * Where signing in to the market stands, as the app shows it: what the login page asks for, what it
 * said went wrong, and a picture of the page for anything else it shows (a captcha, a choice), which
 * the user answers by tapping the picture.
 */
@Serializable
data class SignInStep(
    val step: SignInAsk,
    val error: String? = null,
    /** The page as a JPEG, base64. */
    val picture: String? = null,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
enum class SignInAsk { EMAIL, PASSWORD, CODE, OTHER, DONE }

@Serializable
data class SignInInput(val value: String? = null, val x: Double? = null, val y: Double? = null)

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
)

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

/**
 * What [price] costs the buyer in all through the market's protection: the price, the fee and the
 * shipping the ad states. Offered as the price for paying the seller directly, it hands the seller
 * what the protection would have cost, so the buyer pays the same either way. Rounded down.
 */
fun directPrice(price: Int, shippingEur: Double, protection: BuyerProtection?): Int =
    floor(price + shippingEur + (protection?.let { it.fixedEur + price * it.share } ?: 0.0)).toInt()

/** A market's buyer protection, as the buyer pays for it. */
data class BuyerProtection(val fixedEur: Double, val share: Double)

val PlatformId.buyerProtection: BuyerProtection?
    get() = when (this) {
        PlatformId.KLEINANZEIGEN -> BuyerProtection(fixedEur = 0.50, share = 0.045)
        else -> null
    }

/** The markets whose sellers Arbay can write to. */
val PlatformId.canMessage: Boolean get() = this == PlatformId.KLEINANZEIGEN

/** The fill-ins a text can use, and what each stands for. */
val TEMPLATE_FILL_INS: List<Pair<String, String>> = listOf(
    "{preis}" to "the price to offer",
    "{preis_direkt}" to "the same total paid directly, without buyer protection: the price plus the fee and stated shipping, all to the seller",
    "{preis+20}" to "the price to offer plus 20 € (any amount)",
    "{titel}" to "the ad's title",
    "{versand}" to "shipping as the ad states it",
)

/** [text] with its fill-ins completed for one listing. */
fun fillIn(text: String, price: Int?, title: String, shipping: String?, directPrice: Int? = null): String =
    text.replace("{preis_direkt}", directPrice?.toString() ?: "{preis_direkt}")
        .replace(Regex("""\{preis\+(\d+)}""")) { m -> price?.let { (it + m.groupValues[1].toInt()).toString() } ?: m.value }
        .replace("{preis}", price?.toString() ?: "{preis}")
        .replace("{titel}", title)
        .replace("{versand}", shipping ?: "{versand}")
