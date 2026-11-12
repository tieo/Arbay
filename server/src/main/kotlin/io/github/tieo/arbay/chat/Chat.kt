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
import io.github.tieo.arbay.judging.KleinanzeigenCosts
import io.github.tieo.arbay.model.SendRequest
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

    /** The markets whose account Neuland's account browser holds, by the site NeuAuth signs
     *  it in to; one eBay account answers for every eBay site. */
    private val neulandSites = mapOf(PlatformId.KLEINANZEIGEN to Kleinanzeigen.SITE, PlatformId.EBAY_DE to "ebay.de", PlatformId.WILLHABEN to "willhaben.at", PlatformId.VINTED_DE to "vinted.de")

    /** The Neuland site of [market]'s account, where Neuland holds it. */
    fun neulandSite(market: PlatformId): String? = neulandSites[if (market.name.startsWith("EBAY")) PlatformId.EBAY_DE else market]

    /** Every market the user can sign in to. */
    val markets: List<PlatformId> = neulandSites.keys.toList()

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

    /** The payment service the shops' checkouts hand over to, as Neuland signs in to it. */
    private const val PAYMENT_SITE = "paypal.com"

    /**
     * Signs Neuland's account browser in to the payment service again with the password Neuland
     * keeps, the user confirming in the PayPal app; where Neuland keeps none, the user is asked on
     * the phone. True when it is signed in. Asked only when a buy meets a signed-out PayPal: its web
     * session does not last a day, so keeping it signed in ahead of a buy only asked the user for an
     * approval every day.
     */
    suspend fun reloginPayment(): Boolean {
        if (io.github.tieo.arbay.neuland.NeuAuth.signInAgain(PAYMENT_SITE, "Arbay, to pay with PayPal", waitS = SILENT_SIGN_IN_S, push = false)) return true
        log.warn("PayPal sign-in did not come through")
        askSignIn(io.github.tieo.arbay.model.SignInLink(PlatformId.VINTED_DE, payment = true))
        return false
    }

    // --- account ---

    /** The user's account on [market]: signed in or not, and whether a sign-in is going on. Where
     *  a sign-in through NeuAuth stands shows with the shop accounts' sign-ins. */
    suspend fun account(market: PlatformId = PlatformId.KLEINANZEIGEN): ChatAccount {
        val site = neulandSite(market) ?: throw ChatFailure("Arbay has no account on ${market.displayName}", signedOut = false)
        val shown = if (market.name.startsWith("EBAY")) PlatformId.EBAY_DE else market
        return try {
            val standing = io.github.tieo.arbay.neuland.NeuAuth.standing(site)
            ChatAccount(market = shown, signedIn = standing.signedIn, name = standing.name, signInSite = site)
        } catch (e: io.github.tieo.arbay.neuland.Neuland.Refusal) {
            ChatAccount(market = shown, problem = e.message, signInSite = site)
        }
    }

    /** The user's account on every market Arbay can sign in to, asked of each at once. */
    suspend fun accounts(): List<ChatAccount> = kotlinx.coroutines.coroutineScope {
        markets.map { market -> async { account(market) } }.awaitAll()
    }

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
     * Buys an item at its shop's own checkout ([checkoutSite]: Vinted, eBay, alza, or a web shop
     * Neuland checks out at, [ShopCheckouts]) with the account's saved payment, Vinted's delivered to
     * the door, eBay's to the account's saved address, alza's to the user's pickup point
     * ([ChatSettings.pickup]) for the user's address ([ChatSettings.buyer]), a web shop's to that
     * address, as a guest paying with PayPal; paid only with [PurchaseRequest.pay] and a total within
     * [PurchaseRequest.allInEur], otherwise the checkout is only read and left unpaid. A web shop
     * takes [PurchaseRequest.together] in the same order.
     */
    suspend fun buy(request: PurchaseRequest): Purchase {
        val site = io.github.tieo.arbay.model.checkoutSite(request.url)
            ?: ShopCheckouts.siteOf(request.url, fresh = true)
            ?: throw ChatFailure("Arbay buys at Vinted's, eBay's and alza's checkouts and at web shops Neuland checks out at, and Neuland names no checkout for ${request.url}", signedOut = false)
        require(request.allInEur.isFinite() && request.allInEur > 0) { "an all-in limit of ${request.allInEur} €" }
        if (site !in STATED_CHECKOUTS && site != ShopCheckouts.hostOf(request.url))
            throw ChatFailure("Neuland named $site as the checkout of ${request.url}, another shop", signedOut = false)
        if (request.together.isNotEmpty()) {
            if (site in STATED_CHECKOUTS) throw ChatFailure("${io.github.tieo.arbay.model.checkoutShop(site)} sells one item per order here", signedOut = false)
            val urls = listOf(request.url) + request.together
            if (urls.map { io.github.tieo.arbay.model.productKey(it) }.toSet().size != urls.size) throw ChatFailure("the same item is named twice in one order", signedOut = false)
            request.together.forEach { url ->
                if (ShopCheckouts.siteOf(url, fresh = true) != site) throw ChatFailure("$url is not at $site, so it cannot be in the same order", signedOut = false)
            }
        }
        // A web shop's payment is one run, since each run costs the user a Shop Pay text: Neuland
        // pays only where every amount reads in euros, the rows add up to a total within the limit
        // and the pay button names that total, read twice before the press.
        var bought = checkout(site, request)
        // An answer in another currency is no euro amount, whatever its digits say.
        if (listOfNotNull(bought.item, bought.fee, bought.shipping, bought.total).any { it.currency != "EUR" }) {
            log.error("{}'s checkout answered in another currency than euros: {}", site, bought)
            if (!bought.paid && !bought.placed) throw ChatFailure("$site's checkout is not in euros, so nothing was paid", signedOut = false)
        }
        // PayPal signed out: signed in again with the kept password, the buy is made once more.
        if (request.pay && bought.paymentSignedOut && reloginPayment()) bought = checkout(site, request)
        return Purchase(
            buyable = bought.buyable,
            itemEur = bought.item?.eur(),
            feeEur = bought.fee?.eur(),
            shippingEur = bought.shipping?.eur(),
            totalEur = bought.total?.eur(),
            payment = bought.payment,
            paid = bought.paid,
            problem = bought.problem,
            picture = bought.picture,
            delivery = bought.delivery,
            order = bought.order,
            placed = bought.placed,
        ).also {
            if (it.paid) log.info("Bought {} for {} € (order {})", request.url, it.totalEur, it.order)
            else if (request.pay) keepStopPicture(site, it)
        }
    }

    /** [site]'s checkout of [request]'s item in Neuland's account browser, as it stood. */
    private suspend fun checkout(site: String, request: PurchaseRequest, signedInAgain: Boolean = false): NeulandPurchase {
        val shop = io.github.tieo.arbay.model.checkoutShop(site)
        val body = kotlinx.serialization.json.buildJsonObject {
            if (site in STATED_CHECKOUTS) put("url", JsonPrimitive(request.url))
            else {
                val urls = listOf(request.url) + request.together
                put("urls", kotlinx.serialization.json.JsonArray(urls.map { JsonPrimitive(it) }))
                put("quantities", kotlinx.serialization.json.JsonArray(urls.map { JsonPrimitive(1) }))
            }
            // A double limit becomes whole cents once, here; Neuland works in cents from then on.
            put("mostCents", JsonPrimitive(kotlin.math.round(request.allInEur * 100).toLong()))
            put("pay", JsonPrimitive(request.pay))
            if (site == "alza.de") put("order", alzaOrder())
            else if (site !in STATED_CHECKOUTS) put("order", shopOrder(site))
        }
        val answer = try {
            io.github.tieo.arbay.neuland.Neuland.request(io.ktor.http.HttpMethod.Post, "browsers/${io.github.tieo.arbay.neuland.NeuAuth.BROWSER}/checkout/$site", body, limitS = CHECKOUT_LIMIT_S)
        } catch (e: io.github.tieo.arbay.neuland.Neuland.Refusal) {
            // Signed out of a market that signs in through Google, on the way to a real purchase:
            // signed in again with what Neuland keeps, nothing on the phone unless that fails. Only
            // reading a checkout asks nobody.
            val market = when (site) {
                "vinted.de" -> PlatformId.VINTED_DE
                "ebay.de" -> PlatformId.EBAY_DE
                else -> null
            }
            if (e.signedOut && market != null && request.pay && !signedInAgain) {
                if (io.github.tieo.arbay.neuland.NeuAuth.signInAgain(site, "Arbay, to buy at $shop", waitS = SILENT_SIGN_IN_S, through = "google.com", push = false))
                    return checkout(site, request, signedInAgain = true)
                askSignIn(io.github.tieo.arbay.model.SignInLink(market))
            }
            throw ChatFailure("$shop's checkout: ${e.message}", signedOut = e.signedOut)
        }
        return checkoutJson.decodeFromJsonElement(NeulandPurchase.serializer(), answer)
    }

    /**
     * The page a payment stopped on, kept in the data dir's purchase_stops as Neuland pictured it:
     * the answer carries it once, and a stop at PayPal is understood only from what PayPal showed.
     */
    private fun keepStopPicture(site: String, purchase: Purchase) {
        val picture = purchase.picture ?: return
        runCatching {
            val bytes = java.util.Base64.getDecoder().decode(picture)
            val png = bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()
            val dir = io.github.tieo.arbay.DataDir.file("purchase_stops").apply { mkdirs() }
            val file = java.io.File(dir, "${System.currentTimeMillis()}-$site.${if (png) "png" else "jpg"}")
            file.writeBytes(bytes)
            log.warn("Payment at {} stopped unpaid ({}); its page is kept in {}", site, purchase.problem, file)
        }.onFailure { log.warn("Payment stop picture at {} not kept: {}", site, it.message) }
    }

    /** The checkouts an offer's address names ([io.github.tieo.arbay.model.checkoutSite]), each buying one item per order. */
    private val STATED_CHECKOUTS = setOf("vinted.de", "ebay.de", "alza.de")

    /** The order a web shop's guest checkout delivers: to the user's address, confirmed to their email. */
    private fun shopOrder(site: String): kotlinx.serialization.json.JsonObject {
        val buyer = settings.buyer ?: throw ChatFailure("$site delivers to the buyer's address, and none is set (chat settings: buyer)", signedOut = false)
        val email = buyer.email ?: throw ChatFailure("$site sends its order confirmation to an email, and the buyer has none (chat settings: buyer.email)", signedOut = false)
        return kotlinx.serialization.json.buildJsonObject {
            put("buyer", kotlinx.serialization.json.buildJsonObject {
                put("name", JsonPrimitive(buyer.name))
                put("street", JsonPrimitive(buyer.street))
                put("zip", JsonPrimitive(buyer.zip))
                put("city", JsonPrimitive(buyer.city))
                put("phone", JsonPrimitive(buyer.phone))
                put("email", JsonPrimitive(email))
            })
        }
    }

    /** The order alza's checkout delivers: to the user's pickup point, for the user's address. */
    private fun alzaOrder(): kotlinx.serialization.json.JsonObject {
        val pickup = settings.pickup ?: throw ChatFailure("alza delivers to a pickup point, and none is set (chat settings: pickup)", signedOut = false)
        val buyer = settings.buyer ?: throw ChatFailure("alza's invoice needs the buyer's address, and none is set (chat settings: buyer)", signedOut = false)
        return kotlinx.serialization.json.buildJsonObject {
            put("pickup", kotlinx.serialization.json.buildJsonObject {
                put("near", JsonPrimitive(pickup.near))
                put("address", JsonPrimitive(pickup.address))
                put("kind", JsonPrimitive(pickup.kind))
                pickup.postnummer?.let { put("postnummer", JsonPrimitive(it)) }
            })
            put("buyer", kotlinx.serialization.json.buildJsonObject {
                put("name", JsonPrimitive(buyer.name))
                put("street", JsonPrimitive(buyer.street))
                put("zip", JsonPrimitive(buyer.zip))
                put("city", JsonPrimitive(buyer.city))
                put("phone", JsonPrimitive(buyer.phone))
            })
        }
    }

    private val checkoutJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @kotlinx.serialization.Serializable
    private data class NeulandAmount(val cents: Long, val currency: String = "EUR") {
        fun eur() = cents / 100.0
    }

    @kotlinx.serialization.Serializable
    private data class NeulandPurchase(
        val buyable: Boolean = false,
        val item: NeulandAmount? = null,
        val fee: NeulandAmount? = null,
        val shipping: NeulandAmount? = null,
        val total: NeulandAmount? = null,
        val payment: String? = null,
        val paid: Boolean = false,
        val problem: String? = null,
        @kotlinx.serialization.SerialName("payment_signed_out") val paymentSignedOut: Boolean = false,
        val picture: String? = null,
        val delivery: String? = null,
        val order: String? = null,
        val placed: Boolean = false,
    )

    /**
     * How long a checkout may take before Arbay stops waiting: past Neuland's own 20 minutes for a
     * run, which includes the user approving in PayPal's app. Arbay giving up drops the connection,
     * and a dropped run was abandoned on the shop's checkout form, so the caller always outlasts it.
     */
    private const val CHECKOUT_LIMIT_S = 25 * 60L

    /** How long a sign-in from what Neuland keeps may take before the user is asked on the phone. */
    private const val SILENT_SIGN_IN_S = 90

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
        // A market NeuAuth signs in gets Neuland's own page sent to the phone; the app is
        // opened only where Neuland knows no account to sign in.
        val site = if (link.payment) PAYMENT_SITE else neulandSite(link.market)
        val asker = if (link.payment) "Arbay, to pay at ${link.market.displayName}" else "Arbay, for your ${link.market.displayName} account"
        // Vinted signs in through Google, whose account stands for it until Neuland knows Vinted's own.
        val through = "google.com".takeIf { link.market == PlatformId.VINTED_DE && !link.payment }
        if (site != null && io.github.tieo.arbay.neuland.NeuAuth.askAgain(site, asker, through = through)) return true
        val what = if (link.payment) "PayPal" else link.market.displayName
        return io.github.tieo.arbay.monitoring.Ntfy.send(
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

    fun updateSettings(asked: ChatSettings): ChatSettings = synchronized(settingsFile) {
        // An app built before the pickup point and the buyer's address sends settings without them,
        // which would forget them.
        val new = asked.copy(pickup = asked.pickup ?: settings.pickup, buyer = asked.buyer ?: settings.buyer)
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
