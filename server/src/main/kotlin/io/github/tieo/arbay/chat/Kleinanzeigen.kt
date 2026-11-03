package io.github.tieo.arbay.chat

import io.github.tieo.arbay.model.ChatMessage
import io.github.tieo.arbay.model.Conversation
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.neuland.MarketAccount
import io.github.tieo.arbay.neuland.Neuland
import kotlin.time.Instant

/**
 * The user's Kleinanzeigen account, which Neuland's account browser is signed in to: Neuland reads
 * and writes its conversations, offers and listings as the site's own pages do, and Auth
 * signs it in. This turns Neuland's records into Arbay's and its errors into [ChatFailure]s.
 */
object Kleinanzeigen {
    /** The Neuland site the account is on. */
    const val SITE = "kleinanzeigen.de"

    val account = MarketAccount(io.github.tieo.arbay.neuland.NeulandAuth.BROWSER, SITE)

    /** [work] on the account, a refusal of Neuland's as a [ChatFailure] in its words. */
    suspend fun <T> on(work: suspend MarketAccount.() -> T): T = try {
        account.work()
    } catch (e: Neuland.Refusal) {
        throw ChatFailure(e.message ?: "Neuland refused", signedOut = e.signedOut, detail = e.answer)
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
