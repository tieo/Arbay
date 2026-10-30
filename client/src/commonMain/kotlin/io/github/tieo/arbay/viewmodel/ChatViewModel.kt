package io.github.tieo.arbay.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.BlockReview
import io.github.tieo.arbay.model.ChatAccount
import io.github.tieo.arbay.model.ChatCosts
import io.github.tieo.arbay.model.ChatSettings
import io.github.tieo.arbay.model.Conversation
import io.github.tieo.arbay.model.MessageTemplate
import io.github.tieo.arbay.model.OutgoingMessage
import io.github.tieo.arbay.model.OutgoingState
import io.github.tieo.arbay.model.SendRequest
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Purchase
import io.github.tieo.arbay.model.PurchaseRequest
import io.github.tieo.arbay.model.SignInAsk
import io.github.tieo.arbay.model.SignInInput
import io.github.tieo.arbay.model.SignInStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * The user's conversations with sellers, the messages on their way, and the texts they wrote.
 *
 * Shared by the phone and the web. Nothing here sends on its own: [send] and [reply] are only
 * called from a button the user pressed.
 */
class ChatViewModel(
    private val client: ArbayClient = ArbayClient(),
    sampleAccount: ChatAccount? = null,
    sampleConversations: List<Conversation> = emptyList(),
    sampleOutbox: List<OutgoingMessage> = emptyList(),
    sampleSettings: ChatSettings? = null,
    sampleReview: List<BlockReview> = emptyList(),
    sampleCosts: ChatCosts? = null,
) : ViewModel() {
    private val rendersASample = sampleAccount != null

    private val _account = MutableStateFlow(sampleAccount)
    /** The Kleinanzeigen account, the one the inbox and the seller messages go through. */
    val account: StateFlow<ChatAccount?> = _account.asStateFlow()

    private val _accounts = MutableStateFlow(listOfNotNull(sampleAccount))
    /** The user's account on every market Arbay can sign in to. */
    val accounts: StateFlow<List<ChatAccount>> = _accounts.asStateFlow()

    private val _conversations = MutableStateFlow(sampleConversations)
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _open = MutableStateFlow<Conversation?>(sampleConversations.firstOrNull { it.messages.isNotEmpty() })
    val open: StateFlow<Conversation?> = _open.asStateFlow()

    private val _outbox = MutableStateFlow(sampleOutbox)
    val outbox: StateFlow<List<OutgoingMessage>> = _outbox.asStateFlow()

    private val _settings = MutableStateFlow(sampleSettings ?: ChatSettings())
    val settings: StateFlow<ChatSettings> = _settings.asStateFlow()

    private val _costs = MutableStateFlow(sampleCosts)
    /** What buying through the market costs on top of the price, as the server read it from the market. */
    val costs: StateFlow<ChatCosts?> = _costs.asStateFlow()

    private val _review = MutableStateFlow(sampleReview)
    /** How sellers answered each of the user's text blocks. */
    val review: StateFlow<List<BlockReview>> = _review.asStateFlow()

    fun loadReview() {
        if (rendersASample) return
        viewModelScope.launch { attempt { _review.value = client.textReview() } }
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Listings picked in the results to write to, by id. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    fun toggleSelected(listingId: String) = _selected.update { if (listingId in it) it - listingId else it + listingId }
    fun clearSelection() { _selected.value = emptySet() }

    /** Unread messages over every conversation, for the tab's badge. */
    val unreadTotal: Int get() = _conversations.value.sumOf { it.unread }

    /** The conversation about [listingId], when the user has one. */
    fun conversationFor(listingId: String): Conversation? = _conversations.value.firstOrNull { it.listingId == listingId && it.buying }

    /** The message on its way to [listingId]'s seller, while it is. */
    fun waitingFor(listingId: String): OutgoingMessage? =
        _outbox.value.lastOrNull { it.listingId == listingId && it.state in setOf(OutgoingState.WAITING, OutgoingState.SENDING, OutgoingState.FAILED) }

    private var poller: Job? = null

    /** Keep the inbox and the outbox current while a screen that shows them is open. */
    fun watch() {
        if (rendersASample || poller?.isActive == true) return
        poller = viewModelScope.launch {
            refreshSettings()
            while (isActive) {
                refresh()
                delay(if (_outbox.value.any { it.state == OutgoingState.WAITING || it.state == OutgoingState.SENDING }) 5_000 else 30_000)
            }
        }
    }

    fun refresh() {
        if (rendersASample) return
        viewModelScope.launch {
            _loading.value = true
            attempt {
                val accounts = client.chatAccounts()
                _accounts.value = accounts
                val account = accounts.firstOrNull { it.market == PlatformId.KLEINANZEIGEN } ?: client.chatAccount()
                _account.value = account
                // A sign-in going on in Arbay's browser shows here too, at the step it has reached.
                accounts.firstOrNull { it.signingIn && it.proxySignIn == null }?.let { signing ->
                    if (_signIn.value == null) {
                        _signInMarket.value = signing.market
                        followSignIn()
                    }
                }
                _outbox.value = client.outbox()
                if (account.signedIn) _conversations.value = client.conversations()
            }
            _loading.value = false
        }
    }

    private fun refreshSettings() = viewModelScope.launch {
        attempt { _settings.value = client.chatSettings() }
        attempt { _costs.value = client.chatCosts() }
    }

    fun openConversation(id: String) {
        _open.update { if (it?.id == id) it else _conversations.value.firstOrNull { c -> c.id == id } }
        if (rendersASample) return
        viewModelScope.launch {
            attempt {
                val full = client.conversation(id)
                _open.value = full
                if (full.messages.isNotEmpty() && _conversations.value.firstOrNull { it.id == id }?.unread?.let { it > 0 } == true) {
                    client.markConversationRead(id)
                    _conversations.update { list -> list.map { if (it.id == id) it.copy(unread = 0) else it } }
                }
            }
        }
    }

    fun closeConversation() { _open.value = null }

    fun reply(id: String, text: String, done: () -> Unit = {}) {
        if (rendersASample) return
        viewModelScope.launch {
            attempt {
                client.reply(id, text)
                done()
                _open.value = client.conversation(id)
            }
        }
    }

    fun send(request: SendRequest, done: () -> Unit = {}) {
        if (rendersASample || request.messages.isEmpty()) return
        viewModelScope.launch {
            attempt {
                val queued = client.send(request)
                _outbox.update { it + queued }
                val searchId = request.searchId
                val allIn = request.allInEur
                if (searchId != null && allIn != null) _settings.update { it.copy(allInBySearch = it.allInBySearch + (searchId to allIn)) }
                clearSelection()
                done()
                watch()
            }
        }
    }

    fun cancel(id: String) {
        viewModelScope.launch {
            attempt {
                client.cancelOutgoing(id)
                _outbox.update { list -> list.map { if (it.id == id) it.copy(state = OutgoingState.CANCELLED) else it } }
            }
        }
    }

    fun saveTemplate(template: MessageTemplate) = updateSettings { s ->
        s.copy(templates = if (s.templates.any { it.id == template.id }) s.templates.map { if (it.id == template.id) template else it } else s.templates + template)
    }

    /** Delivery to the door or to a parcel shop, for working out what shipping costs. */
    fun setToDoor(toDoor: Boolean) = updateSettings { it.copy(toDoor = toDoor) }

    fun deleteTemplate(id: String) = updateSettings { s -> s.copy(templates = s.templates.filterNot { it.id == id }) }

    fun newTemplateId(): String = "t${Clock.System.now().toEpochMilliseconds()}"

    private fun updateSettings(change: (ChatSettings) -> ChatSettings) {
        val new = change(_settings.value)
        _settings.value = new
        if (rendersASample) return
        viewModelScope.launch { attempt { _settings.value = client.updateChatSettings(new) } }
    }

    private val _signIn = MutableStateFlow<SignInStep?>(null)
    /** Signing in from inside the app, while it is going on: what the market's page asks for now. */
    val signIn: StateFlow<SignInStep?> = _signIn.asStateFlow()

    private val _signInBusy = MutableStateFlow(false)
    val signInBusy: StateFlow<Boolean> = _signInBusy.asStateFlow()

    private val _signInMarket = MutableStateFlow(PlatformId.KLEINANZEIGEN)
    /** The market the sign-in going on is for; one goes on at a time. */
    val signInMarket: StateFlow<PlatformId> = _signInMarket.asStateFlow()

    /** Starts signing in to [market], or with [payment] to the payment service its checkout uses. */
    fun beginSignIn(market: PlatformId = PlatformId.KLEINANZEIGEN, payment: Boolean = false) {
        signInFollower?.cancel()
        signInRound++
        pendingInputs.clear()
        _signIn.value = null
        _signInMarket.value = market
        signInStep { client.beginChatSignIn(market, payment) }
    }

    /** Starts the sign-in on the site's own pages; the account then carries the page to show, until it is signed in. */
    fun beginProxySignIn() {
        if (rendersASample) return
        viewModelScope.launch { attempt { _account.value = client.beginChatProxySignIn() } }
    }

    /** Hand [value] to the field the page asks for; it goes to the page and is not kept. */
    fun signInWith(value: String) = signInInput(SignInInput(value = value))

    /** Tap the page at a point of its picture, in the page's own pixels. */
    fun signInTap(x: Double, y: Double) = signInInput(SignInInput(x = x, y = y))

    /** Characters typed on the page's picture, passed on as typed. */
    fun signInType(text: String) = signInInput(SignInInput(text = text))

    /** One named key pressed on the page's picture: Return, BackSpace, Tab... */
    fun signInKey(key: String) = signInInput(SignInInput(key = key))

    /** Inputs not yet passed on, in the order given. */
    private val pendingInputs = ArrayDeque<SignInInput>()

    /**
     * Passes [input] on after every input before it. Characters typed while the one before is still
     * on its way go in one request with the ones waiting before them: each request answers with a
     * new picture of the page, and a word sent a key at a time took a second for every key.
     */
    private fun signInInput(input: SignInInput) {
        // Called and drained on the main thread, so the queue needs no lock.
        val last = pendingInputs.lastOrNull()
        // The first input is the one on its way, and is never added to.
        if (input.text != null && last?.text != null && pendingInputs.size > 1) pendingInputs[pendingInputs.lastIndex] = last.copy(text = last.text + input.text)
        else pendingInputs.addLast(input)
        if (pendingInputs.size > 1) return
        val market = _signInMarket.value
        signInStep {
            var step: SignInStep? = null
            while (true) {
                val next = pendingInputs.firstOrNull() ?: break
                try {
                    step = client.chatSignInInput(next, market)
                    pendingInputs.removeFirstOrNull()
                } catch (e: Exception) {
                    // What was typed after a failed input would land out of place; the user types it again.
                    pendingInputs.clear()
                    throw e
                }
            }
            step ?: client.chatSignInStep(market)
        }
    }

    fun cancelSignIn() = viewModelScope.launch {
        signInFollower?.cancel()
        // Keys still on their way belong to the sign-in called off; they go nowhere.
        signInRound++
        _signIn.value = null
        val market = _signInMarket.value
        attempt {
            val left = client.cancelChatSignIn(market)
            if (market == PlatformId.KLEINANZEIGEN) _account.value = left
            _accounts.update { all -> all.map { if (it.market == left.market) left else it } }
        }
    }

    /** One input at a time and in the order given, so keys reach the page as they were typed. */
    private val signInInputs = Mutex()

    /** Counts sign-ins begun and called off, so an input queued for one is dropped once it is over. */
    private var signInRound = 0

    private fun signInStep(call: suspend () -> SignInStep) {
        val round = signInRound
        viewModelScope.launch {
            signInInputs.withLock {
                if (round != signInRound) return@withLock
                _signInBusy.value = true
                attempt {
                    val step = call()
                    if (round == signInRound) {
                        show(step)
                        followSignIn()
                    }
                }
                _signInBusy.value = false
            }
        }
    }

    private fun show(step: SignInStep) {
        _signIn.value = step.takeIf { it.step != SignInAsk.DONE }
        if (step.step == SignInAsk.DONE) refresh()
    }

    private var signInFollower: Job? = null

    /**
     * Keep the shown step the page's own while signing in: the page moves on by itself (a code
     * sent, a check passed) and from other devices, not only from this one's buttons.
     */
    private fun followSignIn() {
        if (rendersASample || signInFollower?.isActive == true) return
        signInFollower = viewModelScope.launch {
            if (_signIn.value == null) attempt { show(client.chatSignInStep(_signInMarket.value)) }
            while (isActive && _signIn.value != null) {
                // Often enough that the picture reads as the page itself.
                delay(1_500)
                if (_signInBusy.value) continue
                attempt { show(client.chatSignInStep(_signInMarket.value)) }
            }
        }
    }

    fun dismissError() { _error.value = null }

    private val _checkout = MutableStateFlow<CheckoutState?>(null)
    /** The Vinted checkout on screen, for one offer at a time. */
    val checkout: StateFlow<CheckoutState?> = _checkout.asStateFlow()

    /** Reads what buying [listing] outright at Vinted's checkout adds up to; pays nothing. */
    fun readCheckout(listing: Listing) = runCheckout(listing, PurchaseRequest(listing.url, Double.MAX_VALUE))

    /**
     * Pays for [listing] at Vinted's checkout, up to the total the user was shown: a checkout that
     * has come to more since is refused by the server before paying.
     */
    fun pay(listing: Listing) {
        val shown = _checkout.value?.takeIf { it.listingId == listing.id }?.purchase?.totalEur ?: return
        runCheckout(listing, PurchaseRequest(listing.url, shown, pay = true))
    }

    private fun runCheckout(listing: Listing, request: PurchaseRequest) {
        if (rendersASample || _checkout.value?.working == true) return
        val before = _checkout.value?.takeIf { it.listingId == listing.id }?.purchase
        _checkout.value = CheckoutState(listing.id, before, working = true, paying = request.pay)
        viewModelScope.launch {
            _checkout.value = try {
                CheckoutState(listing.id, client.buy(request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CheckoutState(listing.id, before, error = e.message ?: "The server could not be reached")
            }
        }
    }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
            _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: "The server could not be reached"
        }
    }
}

/** A Vinted checkout for the offer [listingId]: what it read, and whether a read or a payment is [working]. */
data class CheckoutState(
    val listingId: String,
    val purchase: Purchase? = null,
    val working: Boolean = false,
    val paying: Boolean = false,
    val error: String? = null,
)
