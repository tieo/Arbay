package io.github.tieo.arbay.chat

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.live.Live
import io.github.tieo.arbay.model.LiveKind
import io.github.tieo.arbay.model.BlockReview
import io.github.tieo.arbay.model.ChatAccount
import io.github.tieo.arbay.model.ReviewAnswer
import io.github.tieo.arbay.model.ChatMessage
import io.github.tieo.arbay.model.ChatSettings
import io.github.tieo.arbay.model.Conversation
import io.github.tieo.arbay.model.MessageTemplate
import io.github.tieo.arbay.model.OutgoingMessage
import io.github.tieo.arbay.model.OutgoingState
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SendRequest
import io.github.tieo.arbay.model.SignInAction
import io.github.tieo.arbay.model.SignInAsk
import io.github.tieo.arbay.model.SignInInput
import io.github.tieo.arbay.model.SignInStep
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory

/**
 * The user's Kleinanzeigen conversations, read and written through [ChatBrowser].
 *
 * Several messages asked for at once go out one by one, 30 to 90 seconds apart at random, the pace
 * of someone typing them: a burst of near-identical messages from one account is what a market's
 * automation checks look for. They wait here, on the server, so they still go out when the phone
 * that asked is closed, and any still waiting can be called off.
 */
object Chat {
    private val log = LoggerFactory.getLogger(Chat::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val outboxFile = DataDir.file("chat_outbox.json")
    private val settingsFile = DataDir.file("chat_settings.json")

    private val outbox = mutableListOf<OutgoingMessage>()
    private var settings: ChatSettings
    @Volatile private var signingIn = false

    init {
        outboxFile.readStore(log) { json.decodeFromString<List<OutgoingMessage>>(it) }?.let { stored ->
            // One that was being sent when the server stopped may or may not have gone out; it is
            // reported as failed rather than sent twice.
            outbox += stored.map { if (it.state == OutgoingState.SENDING) it.copy(state = OutgoingState.FAILED, error = "The server restarted while sending") else it }
        }
        settings = settingsFile.readStore(log) { json.decodeFromString<ChatSettings>(it) } ?: ChatSettings(templates = listOf(FIRST_TEXT))
    }

    fun start() {
        scope.launch {
            while (true) {
                runCatching { sendDue() }.onFailure { log.warn("Outbox round failed: {}", it.message) }
                delay(5_000)
            }
        }
    }

    // --- account ---

    suspend fun account(): ChatAccount = try {
        val s = ChatBrowser.call("status")
        ChatAccount(
            signedIn = s["signedIn"]?.bool() == true,
            name = s["name"]?.str(),
            signingIn = signingIn,
            problem = s["problem"]?.str(),
        )
    } catch (e: ChatBrowser.ChatFailure) {
        ChatAccount(signingIn = signingIn, problem = e.message)
    }

    suspend fun beginSignIn(): SignInStep = step(ChatBrowser.call("signin")).also { signingIn = it.step != SignInAsk.DONE }

    suspend fun signInStep(): SignInStep = step(ChatBrowser.call("signin_state"))

    /** Types [input]'s value into what the page asks for, or clicks its point; nothing is kept. */
    suspend fun signInWith(input: SignInInput): SignInStep {
        val answer = if (input.value != null) ChatBrowser.call("signin_fill", 60_000, "value" to input.value)
        else ChatBrowser.call("signin_click", 60_000, "x" to (input.x ?: 0.0), "y" to (input.y ?: 0.0))
        return step(answer).also { if (it.step == SignInAsk.DONE) { signingIn = false; Live.changed(LiveKind.CHAT) } }
    }

    suspend fun cancelSignIn(): ChatAccount {
        signingIn = false
        ChatBrowser.call("signin_done")
        return account()
    }

    private fun step(o: JsonObject) = SignInStep(
        step = runCatching { SignInAsk.valueOf(o["step"]!!.str()!!.uppercase()) }.getOrDefault(SignInAsk.OTHER),
        error = o["error"]?.str(),
        picture = o["picture"]?.str(),
        width = (o["width"] as? JsonPrimitive)?.intOrNull ?: 0,
        height = (o["height"] as? JsonPrimitive)?.intOrNull ?: 0,
        actions = (o["actions"] as? JsonArray).orEmpty().mapNotNull { a ->
            val obj = a as? JsonObject ?: return@mapNotNull null
            SignInAction(
                label = obj["label"]?.str() ?: return@mapNotNull null,
                x = (obj["x"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
                y = (obj["y"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null,
            )
        },
    )

    // --- reading ---

    suspend fun conversations(): List<Conversation> {
        val data = ChatBrowser.call("conversations", 60_000, "page" to 0, "size" to 50)["data"]?.jsonObject ?: return emptyList()
        return data["conversations"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.toConversation() }
    }

    suspend fun conversation(id: String): Conversation? {
        val data = ChatBrowser.call("conversation", 60_000, "id" to id)["data"] as? JsonObject ?: return null
        return data.toConversation(withMessages = true)
    }

    suspend fun markRead(id: String) {
        ChatBrowser.call("read", 30_000, "id" to id)
        Live.changed(LiveKind.CHAT)
    }

    suspend fun reply(id: String, text: String) {
        ChatBrowser.call("reply", 60_000, "id" to id, "text" to text)
        Live.changed(LiveKind.CHAT)
    }

    // --- sending to several sellers ---

    fun queue(request: SendRequest): List<OutgoingMessage> = synchronized(outbox) {
        val now = Clock.System.now()
        // After whatever is still waiting, so a second batch doesn't land between the first's.
        var at = (outbox.filter { it.state == OutgoingState.WAITING }.maxOfOrNull { it.sendAt } ?: now).let { if (it < now) now else it }
        val queued = request.messages.mapIndexed { index, draft ->
            if (index > 0 || at > now) at += Random.nextInt(30, 91).seconds
            OutgoingMessage(UUID.randomUUID().toString(), draft.listingId, draft.title, draft.text, at, blockIds = draft.blockIds, price = draft.price)
        }
        outbox += queued
        trimOutbox()
        persistOutbox()
        val searchId = request.searchId
        val allIn = request.allInEur
        if (searchId != null && allIn != null) {
            updateSettings(settings.copy(allInBySearch = settings.allInBySearch + (searchId to allIn)))
        }
        queued
    }

    fun outbox(): List<OutgoingMessage> = synchronized(outbox) { outbox.toList() }

    fun cancel(id: String): Boolean = synchronized(outbox) {
        val i = outbox.indexOfFirst { it.id == id && it.state == OutgoingState.WAITING }
        if (i < 0) return false
        outbox[i] = outbox[i].copy(state = OutgoingState.CANCELLED)
        persistOutbox()
        true
    }

    private suspend fun sendDue() {
        val due = synchronized(outbox) {
            val i = outbox.indexOfFirst { it.state == OutgoingState.WAITING && it.sendAt <= Clock.System.now() }
            if (i < 0) return
            outbox[i] = outbox[i].copy(state = OutgoingState.SENDING)
            persistOutbox()
            outbox[i]
        }
        val result = runCatching {
            val adId = due.listingId.substringAfter("${PlatformId.KLEINANZEIGEN.name}:", "")
            require(adId.isNotEmpty()) { "Only Kleinanzeigen sellers can be written to" }
            ChatBrowser.call("contact", 120_000, "adId" to adId, "text" to due.text)
        }
        result.getOrNull()?.get("requests")?.let { log.info("Contact form for {} sent: {}", due.listingId, it) }
        synchronized(outbox) {
            val i = outbox.indexOfFirst { it.id == due.id }
            if (i >= 0) outbox[i] = result.fold(
                { due.copy(state = OutgoingState.SENT, conversationId = it["conversationId"]?.str()) },
                { due.copy(state = OutgoingState.FAILED, error = it.message) },
            )
            persistOutbox()
        }
    }

    /** Old finished entries go; what is waiting or failed stays until it's dealt with. */
    private fun trimOutbox() {
        val finished = outbox.filter { it.state == OutgoingState.SENT || it.state == OutgoingState.CANCELLED }
        if (finished.size > 200) outbox.removeAll(finished.sortedBy { it.sendAt }.take(finished.size - 200).toSet())
    }

    private fun persistOutbox() = Live.changed(LiveKind.CHAT).let { runCatching { outboxFile.writeTextAtomically(json.encodeToString(outbox.toList())) }
        .onFailure { log.error("Could not save the outbox: {}", it.message) } }

    // --- how the user's text blocks do ---

    /** Conversations already read for the review, so it does not read every one again each time. */
    private val reviewed = java.util.concurrent.ConcurrentHashMap<String, Pair<Instant, Conversation>>()

    /**
     * For every text block, how many messages it went out in and how sellers answered: the first
     * thing the seller wrote after each message, and how long that took.
     */
    suspend fun review(): List<BlockReview> {
        val sent = outbox().filter { it.state == OutgoingState.SENT && it.conversationId != null && it.blockIds.isNotEmpty() }
        val answers = sent.associate { m ->
            val c = readForReview(m.conversationId!!)
            val first = c?.messages?.firstOrNull { !it.mine && (it.at ?: Instant.DISTANT_PAST) > m.sendAt }
            m.id to ReviewAnswer(m.conversationId!!, m.title, m.sendAt, m.price, first?.text?.ifBlank { if (first.attachments.isNotEmpty()) "(a photo)" else null }, first?.at)
        }
        val names = settings.templates.associate { it.id to it.name }
        return sent.flatMap { m -> m.blockIds.map { it to m } }.groupBy({ it.first }, { it.second }).map { (blockId, messages) ->
            val got = messages.mapNotNull { answers[it.id] }
            val waits = got.mapNotNull { a -> a.answeredAt?.let { (it - a.sentAt).inWholeMinutes } }.sorted()
            BlockReview(
                blockId = blockId,
                name = names[blockId] ?: "A text since deleted",
                sent = messages.size,
                answered = got.count { it.answer != null },
                middleMinutesToAnswer = waits.getOrNull(waits.size / 2),
                answers = got.sortedByDescending { it.sentAt },
            )
        }.sortedByDescending { it.sent }
    }

    private suspend fun readForReview(id: String): Conversation? {
        reviewed[id]?.let { (at, c) -> if (Clock.System.now() - at < 10.minutes) return c }
        val c = runCatching { conversation(id) }.getOrNull() ?: return reviewed[id]?.second
        reviewed[id] = Clock.System.now() to c
        return c
    }

    // --- the user's texts ---

    fun settings(): ChatSettings = settings

    fun updateSettings(new: ChatSettings): ChatSettings = synchronized(settingsFile) {
        settings = new
        Live.changed(LiveKind.CHAT)
        runCatching { settingsFile.writeTextAtomically(json.encodeToString(new)) }
            .onFailure { log.error("Could not save chat settings: {}", it.message) }
        new
    }
}

/** The text the user wrote for asking a seller without buyer protection for proof, as they wrote it,
 *  so it is there before any other has been kept. */
private val FIRST_TEXT = MessageTemplate(
    "ausweis", "Ausweis",
    "Falls ohne Käuferschutz, hätte ich gerne ein Foto mit dem Produkt und dem Ausweis, gerne alles andere außer dem Namen mit zwei Blättern Papier abdecken. Wurde leider in der Vergangenheit Betrugsopfer.",
)

private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

/** A gateway conversation as Arbay shows it. Unknown or missing fields are left empty. */
internal fun JsonObject.toConversation(withMessages: Boolean = false): Conversation? {
    val id = this["id"]?.str() ?: return null
    val buying = this["role"]?.str()?.uppercase() != "SELLER"
    val messages = if (!withMessages) emptyList() else this["messages"]?.jsonArray.orEmpty()
        .mapNotNull { (it as? JsonObject)?.toMessage() }
        .filter { it.text.isNotBlank() || it.attachments.isNotEmpty() || it.offerEur != null }
        .sortedBy { it.at }
    return Conversation(
        id = id,
        listingId = this["adId"]?.str()?.let { "${PlatformId.KLEINANZEIGEN.name}:$it" },
        adTitle = this["adTitle"]?.str(),
        adImage = (this["adImage"] as? JsonPrimitive)?.contentOrNull ?: (this["adImage"] as? JsonObject)?.get("url")?.str(),
        partner = (if (buying) this["sellerName"] else this["buyerName"])?.str(),
        buying = buying,
        unread = (this["unreadMessagesCount"] as? JsonPrimitive)?.intOrNull ?: 0,
        lastAt = this["receivedDate"]?.str()?.let(::parseGatewayTime),
        lastText = (this["textShortTrimmed"] ?: this["textShort"])?.str(),
        messages = messages,
    )
}

private fun JsonObject.toMessage(): ChatMessage? {
    val unichat = (this["unichatMessage"] as? JsonObject)?.get("payload")?.let { it as? JsonObject }
        ?.get("content")?.let { it as? JsonObject }?.get("text")?.str()
    // The body can sit in three places; the short one is a preview, so the longest is the message.
    val text = listOfNotNull(this["textShort"]?.str(), unichat, this["text"]?.str()).maxByOrNull { it.length } ?: ""
    return ChatMessage(
        id = this["messageId"]?.str() ?: return null,
        mine = this["boundness"]?.str() == "OUTBOUND",
        text = text,
        at = this["receivedDate"]?.str()?.let(::parseGatewayTime),
        attachments = (this["attachments"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("url")?.str() },
        offerEur = (this["offeredPriceInEuroCent"] as? JsonPrimitive)?.longOrNull?.let { it / 100.0 },
    )
}

/** The gateway writes times with an offset that may lack its colon (+0200) and with microseconds. */
internal fun parseGatewayTime(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()
    ?: runCatching { Instant.parse(raw.replace(Regex("""([+-]\d{2})(\d{2})$"""), "$1:$2")) }.getOrNull()
