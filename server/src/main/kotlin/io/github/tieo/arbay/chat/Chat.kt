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
 * The user's market accounts: Kleinanzeigen's conversations, offers and sign-in through Neuland
 * ([Kleinanzeigen]), and the eBay, willhaben and Vinted sessions in their market browsers.
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

    /** The markets whose session a browser of Arbay's own holds. */
    val sidecars: Map<PlatformId, MarketSidecar> = listOf(VintedChatBrowser).associateBy { it.market }

    /** The markets whose account Neuland's account browser holds, by the site Neuland Sign signs
     *  it in to; one eBay account answers for every eBay site. */
    private val neulandSites = mapOf(PlatformId.KLEINANZEIGEN to Kleinanzeigen.SITE, PlatformId.EBAY_DE to "ebay.de", PlatformId.WILLHABEN to "willhaben.at")

    /** The Neuland site of [market]'s account, where Neuland holds it. */
    fun neulandSite(market: PlatformId): String? = neulandSites[if (market.name.startsWith("EBAY")) PlatformId.EBAY_DE else market]

    /** Every market the user can sign in to. */
    val markets: List<PlatformId> = neulandSites.keys.toList() + sidecars.keys

    /** The browser of [market], where one of Arbay's own holds its session. */
    fun sidecarFor(market: PlatformId): MarketSidecar =
        sidecars[market] ?: throw ChatFailure("Arbay cannot sign in to ${market.displayName} here; Neuland Sign does", signedOut = false)

    /** The markets a sign-in is going on in. */
    private val signingIn = java.util.concurrent.ConcurrentHashMap.newKeySet<PlatformId>()

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
        scope.launch {
            while (true) {
                delay(3_600_000)
                runCatching { checkPaymentDaily() }.onFailure { log.warn("Payment check failed: {}", it.message) }
            }
        }
    }

    private val paymentCheckFile = DataDir.file("payment_check.txt")

    /**
     * Once a day, whether the payment service Vinted's checkout hands over to still knows Arbay's
     * browser, signing in again with the password its Chrome keeps where it does not: a buy that
     * meets a signed-out PayPal loses the offer while the user is asked. The last check's time is
     * kept on disk, as the server restarts more often than once a day.
     */
    private suspend fun checkPaymentDaily() {
        val last = paymentCheckFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0
        if (System.currentTimeMillis() - last < 24 * 3_600_000L) return
        paymentCheckFile.writeText(System.currentTimeMillis().toString())
        if (VintedChatBrowser.call("status")["payment"] == null) return
        reloginPayment()
    }

    /**
     * Signs the Vinted browser in to its payment service again on its own (see vinted_chat.py's
     * relogin_payment); where that fails, the user is asked on the phone. True when it is signed in.
     */
    suspend fun reloginPayment(): Boolean {
        val o = VintedChatBrowser.call("relogin_payment", 240_000, "waitConfirm" to 150)
        if (o["signedIn"]?.bool() == true) return true
        log.warn("PayPal sign-in for Vinted failed: {}", o["problem"]?.str())
        // The page it stopped on, to read afterwards.
        (o["detail"] as? JsonObject)?.get("picture")?.str()?.let { picture ->
            runCatching { DataDir.file("logs/paypal-signin.jpg").writeBytes(java.util.Base64.getDecoder().decode(picture)) }
        }
        askSignIn(io.github.tieo.arbay.model.SignInLink(PlatformId.VINTED_DE, payment = true))
        return false
    }

    // --- account ---

    /** The user's account on [market]: signed in or not, and whether a sign-in is going on. Where
     *  a sign-in through Neuland Sign stands shows with the shop accounts' sign-ins. */
    suspend fun account(market: PlatformId = PlatformId.KLEINANZEIGEN): ChatAccount {
        neulandSite(market)?.let { site ->
            val shown = if (market.name.startsWith("EBAY")) PlatformId.EBAY_DE else market
            return try {
                val standing = io.github.tieo.arbay.neuland.NeulandSign.standing(site)
                ChatAccount(market = shown, signedIn = standing.signedIn, name = standing.name, signInSite = site)
            } catch (e: io.github.tieo.arbay.neuland.Neuland.Refusal) {
                ChatAccount(market = shown, problem = e.message, signInSite = site)
            }
        }
        val sidecar = sidecarFor(market)
        val signing = sidecar.market in signingIn
        return try {
            val s = sidecar.call("status")
            ChatAccount(
                market = sidecar.market,
                signedIn = s["signedIn"]?.bool() == true,
                name = s["name"]?.str(),
                signingIn = signing,
                problem = s["problem"]?.str(),
                payment = s["payment"]?.str(),
            )
        } catch (e: ChatFailure) {
            ChatAccount(market = sidecar.market, signingIn = signing, problem = e.message)
        }
    }

    /** The user's account on every market Arbay can sign in to, asked of each at once. */
    suspend fun accounts(): List<ChatAccount> = kotlinx.coroutines.coroutineScope {
        markets.map { market -> async { account(market) } }.awaitAll()
    }

    /** Opens [market]'s sign-in page, or with [payment] the sign-in page of the payment service its checkout uses. */
    suspend fun beginSignIn(market: PlatformId = PlatformId.KLEINANZEIGEN, payment: Boolean = false): SignInStep {
        val sidecar = sidecarFor(market)
        return step(sidecar.call(if (payment) "signin_payment" else "signin", 90_000)).also { if (it.step != SignInAsk.DONE) signingIn += sidecar.market }
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
        signingIn -= sidecar.market
        sidecar.call("signin_done")
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

    suspend fun conversations(): List<Conversation> = Kleinanzeigen.on { conversations() }.map { with(Kleinanzeigen) { it.toArbay() } }

    suspend fun conversation(id: String): Conversation? = with(Kleinanzeigen) { on { thread(id) }.toArbay() }

    suspend fun markRead(id: String) {
        Kleinanzeigen.on { markRead(id) }
        Live.changed(LiveKind.CHAT)
    }

    /**
     * Sends [text] in conversation [id]. Neuland counts it as sent only once it shows in the
     * conversation read back, and marks the conversation read, as answering in the app does.
     */
    suspend fun reply(id: String, text: String) {
        Kleinanzeigen.on { reply(id, text) }
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
            Kleinanzeigen.on { message(adId, due.text) }
        }
        result.exceptionOrNull()?.let { log.warn("Message about {} not sent: {}", due.listingId, it.message) }
        synchronized(outbox) {
            val i = outbox.indexOfFirst { it.id == due.id }
            if (i >= 0) outbox[i] = result.fold(
                { due.copy(state = OutgoingState.SENT, conversationId = it.conversation.id) },
                { due.copy(state = OutgoingState.FAILED, error = it.message) },
            )
            persistOutbox()
        }
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
        val options = runCatching { Kleinanzeigen.on { shippingOptions(adId) } }.getOrNull() ?: return null
        return options.map { ShippingOption(it.name, it.price.eur) }.takeIf { it.isNotEmpty() }?.also { shippingByAd[adId] = Clock.System.now() to it }
    }

    /**
     * What the buyer pays for offering [price] in conversation [id], as Kleinanzeigen works it out:
     * price, its fee, and the shipping the buyer picks (to the door the cheapest carrier, else the
     * cheapest way a parcel shop included). Sent only when [send], and then only where that stays
     * within [most].
     */
    private suspend fun offerDialog(id: String, price: Double, most: Double?, send: Boolean): PriceOffer {
        val cents = Math.round(price * 100)
        val listing = Kleinanzeigen.on { thread(id) }.conversation.listing
        val quote = Kleinanzeigen.on { quote(id, cents) }
        val options = runCatching { Kleinanzeigen.on { shippingOptions(listing) } }.getOrNull().orEmpty()
        val shippingEur = (if (settings.toDoor) options.minOfOrNull { it.price.eur } else null) ?: quote.cheapestShipping?.eur
        val paid = Math.round((quote.price.eur + quote.fee.eur + (shippingEur ?: 0.0)) * 100) / 100.0
        val read = PriceOffer(
            price = quote.price.eur,
            fee = quote.fee.eur,
            shippingEur = shippingEur,
            shipping = options.map { ShippingOption(it.name, it.price.eur) },
            paid = paid,
            sent = false,
        )
        if (!send) return read
        if (most != null && paid > most + 0.005) throw ChatFailure("not sent: the buyer would pay $paid €, more than $most €", signedOut = false)
        Kleinanzeigen.on { offer(id, cents) }
        Live.changed(LiveKind.CHAT)
        return read.copy(sent = true)
    }

    /**
     * Buys a Vinted item at its checkout with the account's saved payment method, delivered to the
     * door; paid only with [PurchaseRequest.pay] and a total within [PurchaseRequest.allInEur],
     * otherwise the checkout is only read and left unpaid.
     */
    suspend fun buy(request: PurchaseRequest): Purchase {
        val host = runCatching { java.net.URI(request.url).host }.getOrNull()
        if (host != "www.vinted.de") throw ChatFailure("Arbay buys at Vinted's checkout only, not at $host", signedOut = false)
        var o = VintedChatBrowser.call("buy", 180_000, "url" to request.url, "most" to request.allInEur, "pay" to request.pay)
        // PayPal signed out: signed in again with the saved password, the buy is made once more.
        if (o["paymentSignedOut"]?.bool() == true && reloginPayment()) {
            o = VintedChatBrowser.call("buy", 180_000, "url" to request.url, "most" to request.allInEur, "pay" to request.pay)
        }
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
        ).also {
            if (it.paid) log.info("Bought {} for {} €", request.url, it.totalEur)
        }
    }

    /** When each sign-in was last asked for on the phone, so a run of failed buys sends one push. */
    private val signInAsked = java.util.concurrent.ConcurrentHashMap<io.github.tieo.arbay.model.SignInLink, Long>()

    /**
     * Pushes a request to sign in to the user's phone, opening the app on the sign-in page: an
     * account Arbay needs signed the user out, and only they can sign in again. At most one push an
     * hour for the same sign-in; [force] sends it anyway, for a request the user made just now.
     */
    suspend fun askSignIn(link: io.github.tieo.arbay.model.SignInLink, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val last = signInAsked[link]
        if (!force && last != null && now - last < 3_600_000) return false
        signInAsked[link] = now
        // A market Neuland Sign signs in gets Neuland's own page sent to the phone; the app is
        // opened only where Neuland knows no account to sign in.
        val site = neulandSite(link.market)
        if (site != null && !link.payment && io.github.tieo.arbay.neuland.NeulandSign.askAgain(site, "Arbay, for your ${link.market.displayName} account")) return true
        val what = if (link.payment) sidecarFor(link.market).call("status")["payment"]?.str() ?: "the payment service" else link.market.displayName
        return io.github.tieo.arbay.crawler.Ntfy.send(
            title = "$what needs you to sign in",
            message = if (link.payment) "${link.market.displayName} pays with $what, which has signed Arbay out. Tap to sign in." else "Tap to sign in to ${link.market.displayName} again.",
            click = link.appLink(),
            tags = listOf("key"),
        )
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
