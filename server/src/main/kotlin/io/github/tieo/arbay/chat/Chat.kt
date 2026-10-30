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
import io.github.tieo.arbay.model.PriceOffer
import io.github.tieo.arbay.model.Purchase
import io.github.tieo.arbay.model.PurchaseRequest
import io.github.tieo.arbay.model.ShippingOption
import io.github.tieo.arbay.model.offerWithin
import io.github.tieo.arbay.crawler.KleinanzeigenCosts
import io.github.tieo.arbay.model.SendRequest
import io.github.tieo.arbay.model.SignInAsk
import io.github.tieo.arbay.model.SignInInput
import io.github.tieo.arbay.model.SignInStep
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import io.github.tieo.arbay.signin.ProxyCookie
import io.github.tieo.arbay.signin.SignInHosts
import io.github.tieo.arbay.signin.SignInProxy
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
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

    /** Every market the user can sign in to, each with the browser that holds its session. */
    val sidecars: Map<PlatformId, MarketSidecar> = listOf(ChatBrowser, EbayChatBrowser, WillhabenChatBrowser, VintedChatBrowser).associateBy { it.market }

    /** The browser of [market]; one eBay account answers for every eBay site. */
    fun sidecarFor(market: PlatformId): MarketSidecar =
        sidecars[if (market.name.startsWith("EBAY")) PlatformId.EBAY_DE else market]
            ?: throw ChatFailure("Arbay cannot sign in to ${market.displayName}", signedOut = false)

    /** The markets a sign-in is going on in. */
    private val signingIn = java.util.concurrent.ConcurrentHashMap.newKeySet<PlatformId>()

    /** Sign-ins on the site's own pages, shown through the proxy; their cookies go to the sidecar when done. */
    val signInProxy = SignInProxy(handOver = ::handOverCookies)

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

    /** The user's account on [market]: signed in or not, and whether a sign-in is going on. */
    suspend fun account(market: PlatformId = PlatformId.KLEINANZEIGEN): ChatAccount {
        val sidecar = sidecarFor(market)
        // Only Kleinanzeigen's own pages are shown through the proxy.
        val proxying = sidecar.market == PlatformId.KLEINANZEIGEN && signInProxy.isActive()
        val proxyPath = if (proxying) SignInHosts.START else null
        val signing = sidecar.market in signingIn || proxying
        return try {
            val s = sidecar.call("status")
            ChatAccount(
                market = sidecar.market,
                signedIn = s["signedIn"]?.bool() == true,
                name = s["name"]?.str(),
                signingIn = signing,
                problem = s["problem"]?.str(),
                proxySignIn = proxyPath,
            )
        } catch (e: ChatFailure) {
            ChatAccount(market = sidecar.market, signingIn = signing, problem = e.message, proxySignIn = proxyPath)
        }
    }

    /** The user's account on every market Arbay can sign in to, asked of every market's browser at once. */
    suspend fun accounts(): List<ChatAccount> = kotlinx.coroutines.coroutineScope {
        sidecars.keys.map { market -> async { account(market) } }.awaitAll()
    }

    /** Starts a sign-in on the site's own pages through the proxy; returns the id the browser's cookie carries. */
    fun beginProxySignIn(): String = signInProxy.begin()

    /** The account while a sign-in through the proxy goes on: the browser shows the page it starts at. */
    fun proxyAccount(): ChatAccount = ChatAccount(market = PlatformId.KLEINANZEIGEN, signingIn = true, proxySignIn = SignInHosts.START)

    /** Gives the cookies of a proxied sign-in to the sidecar's browser; true when that browser is then signed in. */
    private suspend fun handOverCookies(cookies: List<ProxyCookie>): Boolean {
        val answer = ChatBrowser.call("set_cookies", 60_000, "cookies" to JsonArray(cookies.map { it.toJson() }))
        val signedIn = answer["signedIn"]?.bool() == true
        if (signedIn) Live.changed(LiveKind.CHAT)
        return signedIn
    }

    /**
     * Signs [market]'s browser in with the cookies of a session the user already has in a browser of
     * their own, handed over whole; no password passes. The account as it then stands.
     */
    suspend fun takeSession(market: PlatformId, cookies: JsonArray): ChatAccount {
        val sidecar = sidecarFor(market)
        sidecar.call("set_cookies", 90_000, "cookies" to cookies)
        signingIn -= sidecar.market
        Live.changed(LiveKind.CHAT)
        return account(market)
    }

    suspend fun beginSignIn(market: PlatformId = PlatformId.KLEINANZEIGEN): SignInStep {
        val sidecar = sidecarFor(market)
        return step(sidecar.call("signin", 90_000)).also { if (it.step != SignInAsk.DONE) signingIn += sidecar.market }
    }

    suspend fun signInStep(market: PlatformId = PlatformId.KLEINANZEIGEN): SignInStep =
        step(sidecarFor(market).call("signin_state")).also { finishedIfDone(market, it) }

    /** Types [input]'s value into what the page asks for, or clicks its point; nothing is kept. */
    suspend fun signInWith(input: SignInInput, market: PlatformId = PlatformId.KLEINANZEIGEN): SignInStep {
        val sidecar = sidecarFor(market)
        val answer = when {
            input.value != null -> sidecar.call("signin_fill", 60_000, "value" to input.value)
            input.text != null || input.key != null -> sidecar.call("signin_keys", 60_000, "text" to input.text, "key" to input.key)
            else -> sidecar.call("signin_click", 60_000, "x" to (input.x ?: 0.0), "y" to (input.y ?: 0.0))
        }
        return step(answer).also { finishedIfDone(market, it) }
    }

    /** A page that has moved on to the signed-in site, by any device's input or by itself, ends the sign-in. */
    private fun finishedIfDone(market: PlatformId, step: SignInStep) {
        if (step.step == SignInAsk.DONE && signingIn.remove(sidecarFor(market).market)) Live.changed(LiveKind.CHAT)
    }

    suspend fun cancelSignIn(market: PlatformId = PlatformId.KLEINANZEIGEN): ChatAccount {
        val sidecar = sidecarFor(market)
        val proxied = sidecar.market == PlatformId.KLEINANZEIGEN && signInProxy.end()
        signingIn -= sidecar.market
        // A sign-in through the proxy has no page open in the sidecar's browser to leave.
        if (!proxied) sidecar.call("signin_done")
        return account(market)
    }

    private fun step(o: JsonObject) = SignInStep(
        step = runCatching { SignInAsk.valueOf(o["step"]!!.str()!!.uppercase()) }.getOrDefault(SignInAsk.OTHER),
        error = o["error"]?.str(),
        picture = o["picture"]?.str(),
        width = (o["width"] as? JsonPrimitive)?.intOrNull ?: 0,
        height = (o["height"] as? JsonPrimitive)?.intOrNull ?: 0,
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

    /**
     * Sends [text] in conversation [id] and reads the conversation back until the reply shows in it.
     * The gateway answers a post it accepted with success whatever then becomes of the message, so
     * only the read back counts as sent; a reply that does not show fails with the gateway's answer.
     * A conversation answered is a conversation read, as it is after answering in the app.
     */
    suspend fun reply(id: String, text: String) {
        val before = runCatching { conversation(id) }.getOrNull()?.messages.orEmpty().map { it.id }.toSet()
        val sentAt = Clock.System.now()
        val answer = ChatBrowser.call("reply", 60_000, "id" to id, "text" to text)["answer"]
        Live.changed(LiveKind.CHAT)
        repeat(3) { attempt ->
            if (attempt > 0) delay(2.seconds)
            if (replyShows(conversation(id), before, text, sentAt)) {
                runCatching { markRead(id) }
                return
            }
        }
        throw ChatFailure("the reply was sent but does not show in the conversation; Kleinanzeigen answered: $answer", signedOut = false)
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
        (result.exceptionOrNull() as? ChatFailure)?.detail?.let { keepFailedPage(due, it, result.exceptionOrNull() as Exception) }
        synchronized(outbox) {
            val i = outbox.indexOfFirst { it.id == due.id }
            if (i >= 0) outbox[i] = result.fold(
                { due.copy(state = OutgoingState.SENT, conversationId = it["conversationId"]?.str()) },
                { due.copy(state = OutgoingState.FAILED, error = it.message) },
            )
            persistOutbox()
        }
    }

    /** The ad page a message failed on, with what it sent, as an error snapshot to read afterwards. */
    private fun keepFailedPage(due: OutgoingMessage, detail: JsonObject, error: Exception) {
        val requests = detail["requests"]?.toString().orEmpty()
        io.github.tieo.arbay.crawler.ErrorSnapshotStore.capture(
            platform = "KLEINANZEIGEN_CHAT",
            query = due.listingId,
            error = RuntimeException("${error.message}; the page sent: $requests"),
            errorType = io.github.tieo.arbay.crawler.ErrorType.PARSE_ERROR,
            url = detail["url"]?.str(),
            fetchStage = "contact",
            html = detail["html"]?.str(),
        )
    }

    // --- price offers through "Sicher bezahlen" ---

    private val shippingByAd = java.util.concurrent.ConcurrentHashMap<String, Pair<Instant, List<ShippingOption>>>()

    /**
     * The ways ad [adId] ships through "Sicher bezahlen", priced as the buyer pays at payment, read
     * through the signed-in browser and kept for an hour. Null when that cannot be read, signed out
     * or for an ad without "Sicher bezahlen".
     */
    suspend fun shippingOptions(adId: String): List<ShippingOption>? {
        shippingByAd[adId]?.takeIf { Clock.System.now() - it.first < 1.hours }?.let { return it.second }
        val data = runCatching { ChatBrowser.call("shipping_options", 30_000, "adId" to adId)["data"] as? JsonObject }
            .getOrNull() ?: return null
        val options = (data["options"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val cents = (o["priceInEuroCent"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            ShippingOption(listOfNotNull(o["carrierName"]?.str(), o["name"]?.str()).joinToString(" "), cents / 100.0)
        }
        return options.takeIf { it.isNotEmpty() }?.also { shippingByAd[adId] = Clock.System.now() to it }
    }

    /** The conversation's price dialog filled with [price] and read back; sent only when [send]. */
    private suspend fun offerDialog(id: String, price: Double, most: Double?, send: Boolean): PriceOffer {
        val o = ChatBrowser.call(
            "offer", 150_000, "id" to id, "price" to price, "toDoor" to settings.toDoor, "most" to most, "send" to send,
        )
        fun num(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull
        return PriceOffer(
            price = num("price") ?: price,
            fee = num("fee"),
            shippingEur = num("shippingEur"),
            shipping = (o["shipping"] as? JsonArray).orEmpty().mapNotNull { e ->
                val x = e as? JsonObject ?: return@mapNotNull null
                ShippingOption(x["name"]?.str() ?: return@mapNotNull null, (x["eur"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null)
            },
            paid = num("paid"),
            sent = o["sent"]?.bool() == true,
            picture = o["picture"]?.str(),
        ).also { if (send) Live.changed(LiveKind.CHAT) }
    }

    /**
     * Buys a Vinted item at its checkout with the account's saved payment method, delivered to the
     * door; paid only with [PurchaseRequest.pay] and a total within [PurchaseRequest.allInEur],
     * otherwise the checkout is only read and left unpaid.
     */
    suspend fun buy(request: PurchaseRequest): Purchase {
        val host = runCatching { java.net.URI(request.url).host }.getOrNull()
        if (host != "www.vinted.de") throw ChatFailure("Arbay buys at Vinted's checkout only, not at $host", signedOut = false)
        val o = VintedChatBrowser.call("buy", 180_000, "url" to request.url, "most" to request.allInEur, "pay" to request.pay)
        fun num(key: String) = (o[key] as? JsonPrimitive)?.doubleOrNull
        return Purchase(
            buyable = o["buyable"]?.bool() == true,
            itemEur = num("item"),
            feeEur = num("fee"),
            shippingEur = num("shipping"),
            totalEur = num("total"),
            payment = o["payment"]?.str(),
            paid = o["paid"]?.bool() == true,
            problem = o["problem"]?.str(),
            picture = o["picture"]?.str(),
        ).also { if (it.paid) log.info("Bought {} for {} €", request.url, it.totalEur) }
    }

    /**
     * The highest whole-euro price whose payment, as the conversation's own dialog adds it up (price,
     * fee and the shipping the buyer picks), stays within [allInEur]; nothing is sent. The first try
     * leaves shipping out, so it is the most the limit could allow; the dialog then says what the
     * shipping and the fee come to, and the price comes down until the payment fits.
     */
    suspend fun fitOffer(id: String, allInEur: Double): PriceOffer {
        val protection = KleinanzeigenCosts.costs().protection
        var price = offerWithin(allInEur, 0.0, protection)
        var read = offerDialog(id, price.toDouble(), null, send = false)
        repeat(4) {
            val paid = read.paid ?: return read
            if (paid <= allInEur + 0.005) return read
            price = minOf(offerWithin(allInEur, read.shippingEur ?: 0.0, protection), price - 1)
            read = offerDialog(id, price.toDouble(), null, send = false)
        }
        return read
    }

    /** Sends [price] as a price offer, refused before sending if the payment would pass [allInEur]. */
    suspend fun sendOffer(id: String, price: Double, allInEur: Double): PriceOffer =
        offerDialog(id, price, allInEur, send = true)

    /** Opens an ad's message dialog without typing or sending, to see that it is there. */
    suspend fun contactPreview(adId: String): JsonObject = ChatBrowser.call("contact_preview", 90_000, "adId" to adId)

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

private fun ProxyCookie.toJson(): JsonObject = buildJsonObject {
    put("name", name)
    put("value", value)
    put("domain", if (hostOnly) domain else ".$domain")
    put("path", path)
    put("secure", secure)
    put("httpOnly", httpOnly)
    expires?.let { put("expires", it.toEpochMilliseconds() / 1000.0) }
}

private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

/** A gateway conversation as Arbay shows it. Unknown or missing fields are left empty. */
internal fun JsonObject.toConversation(withMessages: Boolean = false): Conversation? {
    val id = this["id"]?.str() ?: return null
    val buying = this["role"]?.str()?.uppercase() != "SELLER"
    val messages = if (!withMessages) emptyList() else this["messages"]?.jsonArray.orEmpty()
        .mapNotNull { (it as? JsonObject)?.toMessage(buying) }
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

/** One gateway message; [buying] says which side of the conversation the user is on. */
private fun JsonObject.toMessage(buying: Boolean): ChatMessage? {
    val unichat = (this["unichatMessage"] as? JsonObject)?.get("payload")?.let { it as? JsonObject }
        ?.get("content")?.let { it as? JsonObject }?.get("text")?.str()
    // The body can sit in three places; the short one is a preview, so the longest is the message.
    val text = listOfNotNull(this["textShort"]?.str(), unichat, this["text"]?.str()).maxByOrNull { it.length } ?: ""
    return ChatMessage(
        id = this["messageId"]?.str() ?: return null,
        // A price offer through "Sicher bezahlen" carries no direction, only whether the buyer made it.
        mine = this["boundness"]?.str()?.let { it == "OUTBOUND" } ?: (this["isBuyerOffer"]?.bool() == buying),
        text = text,
        at = this["receivedDate"]?.str()?.let(::parseGatewayTime),
        attachments = (this["attachments"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("url")?.str() },
        offerEur = (this["offeredPriceInEuroCent"] as? JsonPrimitive)?.longOrNull?.let { it / 100.0 },
    )
}

/**
 * Whether [after], a conversation read back after sending [text] at [sentAt], holds that reply: a
 * message of ours with the same text that was not among the [before] message ids and is dated no
 * earlier than the send, give or take a minute between this clock and Kleinanzeigen's.
 */
internal fun replyShows(after: Conversation?, before: Set<String>, text: String, sentAt: Instant): Boolean =
    after?.messages.orEmpty().any { m ->
        val at = m.at
        m.mine && m.id !in before && m.text.trim() == text.trim() && at != null && at >= sentAt - 1.minutes
    }

/** The gateway writes times with an offset that may lack its colon (+0200) and with microseconds. */
internal fun parseGatewayTime(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()
    ?: runCatching { Instant.parse(raw.replace(Regex("""([+-]\d{2})(\d{2})$"""), "$1:$2")) }.getOrNull()
