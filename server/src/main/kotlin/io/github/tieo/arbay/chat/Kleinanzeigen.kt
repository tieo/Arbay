package io.github.tieo.arbay.chat

import io.github.tieo.arbay.model.ChatMessage
import io.github.tieo.arbay.model.Conversation
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.neuland.MarketAccount
import io.github.tieo.arbay.neuland.Neuland
import io.ktor.http.HttpMethod
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The user's Kleinanzeigen account, which Neuland's account browser is signed in to: Neuland reads
 * and writes its conversations, offers and listings as the site's own pages do, and Neuland Sign
 * signs it in. This turns Neuland's records into Arbay's and its errors into [ChatFailure]s.
 */
object Kleinanzeigen {
    /** The Neuland site the account is on. */
    const val SITE = "kleinanzeigen.de"

    /** Neuland's browser the user's own accounts are signed in to. */
    private const val BROWSER = "accounts"

    val account = MarketAccount(BROWSER, SITE)

    /** [work] on the account, a refusal of Neuland's as a [ChatFailure] in its words. */
    suspend fun <T> on(work: suspend MarketAccount.() -> T): T = try {
        account.work()
    } catch (e: Neuland.Refusal) {
        throw ChatFailure(e.message ?: "Neuland refused", signedOut = e.signedOut, detail = e.answer)
    }

    /**
     * Asks Neuland Sign to sign the account in again, Neuland sending its sign-in page to the
     * phone: with the account the site was last signed in to with. False where Neuland knows none
     * (the app then asks for it) or did not take the request.
     */
    suspend fun askSignIn(): Boolean {
        val known = Neuland.passOn(HttpMethod.Get, "accounts", null)?.second ?: return false
        val account = runCatching { Json.parseToJsonElement(known) }.getOrNull()
            ?.let { (it as? JsonObject)?.get("accounts") as? JsonObject }
            ?.get(SITE)?.let { (it as? JsonPrimitive)?.content } ?: return false
        val body = buildJsonObject {
            put("site", JsonPrimitive(SITE))
            put("account", JsonPrimitive(account))
            put("browser", JsonPrimitive(BROWSER))
            put("asker", JsonPrimitive("Arbay, for your Kleinanzeigen messages"))
        }
        val (status, _) = Neuland.passOn(HttpMethod.Post, "sign-ins", body.toString()) ?: return false
        return status in 200..299
    }

    /** The listing id Arbay keeps for one of the site's ads. */
    fun listingId(ad: String) = "${PlatformId.KLEINANZEIGEN.name}:$ad"

    /** A conversation as Arbay shows it; with its messages where [thread] carries them. */
    fun MarketAccount.Conversation.toArbay(thread: MarketAccount.Thread? = null): Conversation {
        val buying = side == "buying"
        return Conversation(
            id = id,
            listingId = listing.takeIf { it.isNotEmpty() }?.let(::listingId),
            adTitle = title.ifEmpty { null },
            adImage = image,
            partner = partner.ifEmpty { null },
            buying = buying,
            unread = unread,
            lastAt = latestAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
            lastText = latest,
            messages = thread?.messages.orEmpty().map { it.toArbay(buying) },
        )
    }

    /** One message; one of the site's own notices (an offer made through it) is the user's where
     *  the side it names is the user's side of the conversation. */
    private fun MarketAccount.Message.toArbay(buying: Boolean) = ChatMessage(
        id = id,
        mine = when (author) {
            "me" -> true
            "site" -> notice?.by == (if (buying) "buying" else "selling")
            else -> false
        },
        text = text,
        at = at?.let { runCatching { Instant.parse(it) }.getOrNull() },
        attachments = pictures,
        offerEur = offer?.eur,
    )

    fun MarketAccount.Thread.toArbay(): Conversation = conversation.toArbay(this)
}
