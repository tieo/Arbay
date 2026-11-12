package io.github.tieo.arbay.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.BlockReview
import io.github.tieo.arbay.model.ChatAccount
import io.github.tieo.arbay.model.SiteSignIns
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
                _outbox.value = client.outbox()
                if (account.signedIn) _conversations.value = client.conversations()
            }
            // Neuland's sign-ins answer apart: an Arbay without Neuland still shows its markets.
            runCatching { _sites.value = client.siteSignIns() }
            if (_sites.value?.steps?.values?.any { it.running } == true) followSites()
            _loading.value = false
        }
    }

    private val _sites = MutableStateFlow<SiteSignIns?>(null)

    /** Neuland's key and where signing its account browser in to each of the user's accounts stands. */
    val sites: StateFlow<SiteSignIns?> = _sites.asStateFlow()

    private var siteFollower: Job? = null

    /** Looks at the sign-ins every two seconds while one is under way, so each step shows as it comes. */
    private fun followSites() {
        if (siteFollower?.isActive == true) return
        siteFollower = viewModelScope.launch {
            do {
                delay(2_000)
                runCatching { _sites.value = client.siteSignIns() }
            } while (_sites.value?.steps?.values?.any { it.running } == true)
        }
    }

    /** Asks NeuAuth for a sign-in to [site] as [account] and hands its page to [open], where
     *  the password is given and every later step shows. */
    fun signInSite(site: String, account: String, open: (String) -> Unit) = viewModelScope.launch {
        attempt {
            val asked = client.askSiteSignIn(site, account.trim())
            open(asked.page)
            _sites.value = client.siteSignIns()
            followSites()
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

    fun dismissError() { _error.value = null }

    private val _checkout = MutableStateFlow<CheckoutState?>(null)
    /** The shop checkout on screen, for one offer at a time. */
    val checkout: StateFlow<CheckoutState?> = _checkout.asStateFlow()

    /** Reads what buying [listing] outright at its shop's checkout adds up to, with [together] in the same order; pays nothing. */
    fun readCheckout(listing: Listing, together: List<Listing> = emptyList()) =
        runCheckout(listing, together, PurchaseRequest(listing.url, Double.MAX_VALUE, together = together.map { it.url }))

    /** Puts [other], an offer at the same web shop, into [listing]'s order or takes it out, and reads the checkout again. */
    fun toggleTogether(listing: Listing, other: Listing) {
        val now = _checkout.value?.takeIf { it.listingId == listing.id }?.together.orEmpty()
        readCheckout(listing, if (now.any { it.id == other.id }) now.filter { it.id != other.id } else now + other)
    }

    /**
     * Pays for [listing] at its shop's checkout, with the items read together with it, up to the
     * total the user was shown: a checkout that has come to more since is refused by the server
     * before paying.
     */
    fun pay(listing: Listing) {
        val read = _checkout.value?.takeIf { it.listingId == listing.id && !it.working } ?: return
        val shown = read.purchase?.totalEur ?: return
        runCheckout(listing, read.together, PurchaseRequest(listing.url, shown, pay = true, together = read.together.map { it.url }))
    }

    private fun runCheckout(listing: Listing, together: List<Listing>, request: PurchaseRequest) {
        if (rendersASample || _checkout.value?.working == true) return
        val before = _checkout.value?.takeIf { it.listingId == listing.id }
        // A different basket is a different checkout: its old total is never shown as this one's.
        val shown = before?.purchase?.takeIf { before.together.map { it.id } == together.map { it.id } }
        _checkout.value = CheckoutState(listing.id, shown, together, working = true, paying = request.pay)
        viewModelScope.launch {
            _checkout.value = try {
                CheckoutState(listing.id, client.buy(request), together)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A payment that failed may still have ordered: its old total is no longer offered to pay
                // again, and the checkout is read anew first.
                CheckoutState(listing.id, shown.takeIf { !request.pay }, together, error = e.message ?: "The server could not be reached")
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

/** A shop checkout for the offer [listingId]: what it read, and whether a read or a payment is [working]. */
data class CheckoutState(
    val listingId: String,
    val purchase: Purchase? = null,
    /** The other offers at the same web shop in this order, one of each. */
    val together: List<Listing> = emptyList(),
    val working: Boolean = false,
    val paying: Boolean = false,
    val error: String? = null,
)
