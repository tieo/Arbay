package io.github.tieo.arbay.chat

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.crawler.Ntfy
import io.github.tieo.arbay.model.Conversation
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * One message a buyer or seller sent the user, as the watch first saw it.
 * [seq] orders every message the watch has reported, so a reader can ask for what came after.
 */
@Serializable
data class IncomingMessage(
    val seq: Long,
    val conversationId: String,
    val messageId: String,
    val partner: String? = null,
    val adTitle: String? = null,
    val listingId: String? = null,
    /** Whether the user is the one buying in this conversation. */
    val buying: Boolean,
    val text: String,
    val at: Instant? = null,
    val offerEur: Double? = null,
)

/**
 * Notices a new message in any Kleinanzeigen conversation of the user's and says so: one push to
 * the phone for each round that found any ([Ntfy], the topic the saved-search alerts use), and the
 * messages themselves to whoever asks [after] a point, which is how an assistant on another machine
 * hears about them.
 *
 * A message counts once, by its id: the conversation list's unread count says where to look, and
 * the conversation itself says which of its messages are unread and from the other side. Nothing
 * here marks anything read, so the unread mark stays for the user's own reading.
 *
 * The first round on a server with no record learns what is already unread without a push; those
 * were waiting before the watch existed.
 */
object ChatWatch {
    private val log = LoggerFactory.getLogger(ChatWatch::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How often the conversation list is read: one request to the market's message service. */
    private val EVERY = 2.minutes

    /** The messages kept for readers that were away, newest last. */
    private const val KEPT = 200

    @Serializable
    private data class State(
        /** Per conversation, the ids of the other side's messages already reported. */
        val reported: Map<String, Set<String>> = emptyMap(),
        val messages: List<IncomingMessage> = emptyList(),
    )

    private val stateFile = DataDir.file("chat_incoming.json")
    private var state: State
    private val first: Boolean
    private val latest = MutableStateFlow(0L)

    /** The last time each conversation was read, by its newest message time, so an unchanged one is not read again. */
    private val readAt = HashMap<String, Instant?>()

    init {
        val stored = stateFile.readStore(log) { json.decodeFromString<State>(it) }
        first = stored == null
        state = stored ?: State()
        latest.value = state.messages.maxOfOrNull { it.seq } ?: 0L
    }

    fun start() {
        scope.launch {
            var learning = first
            while (true) {
                try {
                    round(push = !learning)
                    learning = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ChatFailure) {
                    // Signed out or the chat browser restarting: the next round tries again.
                    log.debug("chat watch round skipped: {}", e.message)
                } catch (e: Exception) {
                    log.warn("chat watch round failed: {}", e.message)
                }
                delay(EVERY)
            }
        }
    }

    /** The messages reported after [after], waiting up to [wait] for one when there are none yet. */
    suspend fun after(after: Long, wait: Duration): List<IncomingMessage> {
        if (latest.value <= after && wait.isPositive()) withTimeoutOrNull(wait) { latest.first { it > after } }
        return synchronized(this) { state.messages.filter { it.seq > after } }
    }

    /** Every message from the other side the user has not read, read now; nothing is kept or marked. */
    suspend fun unreadNow(): List<IncomingMessage> = Chat.conversations().filter { it.unread > 0 }.flatMap { summary ->
        val conversation = Chat.conversation(summary.id) ?: return@flatMap emptyList()
        unreadFromThem(conversation.copy(unread = summary.unread), emptySet()).map { m ->
            IncomingMessage(
                seq = 0, conversationId = conversation.id, messageId = m.id,
                partner = conversation.partner ?: summary.partner, adTitle = conversation.adTitle ?: summary.adTitle,
                listingId = conversation.listingId ?: summary.listingId, buying = conversation.buying,
                text = m.text, at = m.at, offerEur = m.offerEur,
            )
        }
    }

    /** One look at every conversation; new messages are kept and, when [push], sent to the phone. */
    private suspend fun round(push: Boolean) {
        val conversations = Chat.conversations()
        val found = mutableListOf<IncomingMessage>()
        for (summary in conversations.filter { it.unread > 0 }) {
            if (summary.id in readAt && readAt[summary.id] == summary.lastAt) continue
            val conversation = Chat.conversation(summary.id) ?: continue
            readAt[summary.id] = summary.lastAt
            val known = synchronized(this) { state.reported[summary.id].orEmpty() }
            found += unreadFromThem(conversation.copy(unread = summary.unread), known).map { m ->
                IncomingMessage(
                    seq = 0, conversationId = conversation.id, messageId = m.id,
                    partner = conversation.partner ?: summary.partner,
                    adTitle = conversation.adTitle ?: summary.adTitle,
                    listingId = conversation.listingId ?: summary.listingId,
                    buying = conversation.buying, text = m.text, at = m.at, offerEur = m.offerEur,
                )
            }
        }
        if (found.isEmpty()) return
        val numbered = synchronized(this) {
            var seq = latest.value
            val numbered = found.map { it.copy(seq = ++seq) }
            val reported = state.reported.toMutableMap()
            numbered.forEach { m -> reported[m.conversationId] = reported[m.conversationId].orEmpty() + m.messageId }
            // A conversation gone from the list has nothing left to report.
            val listed = conversations.map { it.id }.toSet()
            state = State(reported.filterKeys { it in listed }, (state.messages + numbered).takeLast(KEPT))
            runCatching { stateFile.writeTextAtomically(json.encodeToString(State.serializer(), state)) }
                .onFailure { log.error("could not write the chat watch's record: {}", it.message) }
            numbered
        }
        latest.value = numbered.last().seq
        log.info("chat watch: {} new message(s){}", numbered.size, if (push) "" else ", learned without a push")
        if (push) Ntfy.send(pushTitle(numbered), pushBody(numbered), click = conversationUrl(numbered.first().conversationId), tags = listOf("speech_balloon"))
    }

    private fun conversationUrl(id: String) = "https://www.kleinanzeigen.de/m-nachrichten.html?conversationId=$id"
}

/**
 * The messages in [conversation] the user has not read and the other side wrote, minus those
 * already [reported]. The market counts a conversation's unread messages, and they are its last
 * ones from the other side.
 */
internal fun unreadFromThem(conversation: Conversation, reported: Set<String>) =
    conversation.messages.filter { !it.mine }.takeLast(conversation.unread).filter { it.id !in reported }

/** "Anna · Pixel 9 Pro XL" for one message, "3 new messages on Kleinanzeigen" for several. */
internal fun pushTitle(messages: List<IncomingMessage>): String {
    val people = messages.map { it.conversationId }.distinct()
    return if (people.size == 1) listOfNotNull(messages.first().partner, messages.first().adTitle).joinToString(" · ").ifEmpty { "Kleinanzeigen" }
    else "${messages.size} new messages on Kleinanzeigen"
}

/** Each message quoted: who, about which ad, and what they wrote; an offer made through the market's button with its amount. */
internal fun pushBody(messages: List<IncomingMessage>): String {
    val one = messages.map { it.conversationId }.distinct().size == 1
    return messages.joinToString("\n") { m ->
        val what = m.text.ifBlank { m.offerEur?.let { "offers %.2f €".format(it) } ?: "(a picture)" }.replace(Regex("""\s+"""), " ").take(300)
        if (one) what else "${m.partner ?: "?"} (${m.adTitle ?: "?"}): $what"
    }
}
