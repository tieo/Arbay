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
    val account: StateFlow<ChatAccount?> = _account.asStateFlow()

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
                val account = client.chatAccount()
                _account.value = account
                // A sign-in going on in Arbay's browser shows here too, at the step it has reached.
                if (account.signingIn && account.proxySignIn == null && _signIn.value == null) followSignIn()
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

    fun beginSignIn() = signInStep { client.beginChatSignIn() }

    /** Starts the sign-in on the site's own pages; the account then carries the page to show, until it is signed in. */
    fun beginProxySignIn() {
        if (rendersASample) return
        viewModelScope.launch { attempt { _account.value = client.beginChatProxySignIn() } }
    }

    /** Hand [value] to the field the page asks for; it goes to the page and is not kept. */
    fun signInWith(value: String) = signInStep { client.chatSignInInput(SignInInput(value = value)) }

    /** Tap the page at a point of its picture, in the page's own pixels. */
    fun signInTap(x: Double, y: Double) = signInStep { client.chatSignInInput(SignInInput(x = x, y = y)) }

    /** Characters typed on the page's picture, passed on as typed. */
    fun signInType(text: String) = signInStep { client.chatSignInInput(SignInInput(text = text)) }

    /** One named key pressed on the page's picture: Return, BackSpace, Tab... */
    fun signInKey(key: String) = signInStep { client.chatSignInInput(SignInInput(key = key)) }

    fun cancelSignIn() = viewModelScope.launch {
        signInFollower?.cancel()
        _signIn.value = null
        attempt { _account.value = client.cancelChatSignIn() }
    }

    /** One input at a time and in the order given, so keys reach the page as they were typed. */
    private val signInInputs = Mutex()

    private fun signInStep(call: suspend () -> SignInStep) = viewModelScope.launch {
        signInInputs.withLock {
            _signInBusy.value = true
            attempt {
                show(call())
                followSignIn()
            }
            _signInBusy.value = false
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
            if (_signIn.value == null) attempt { show(client.chatSignInStep()) }
            while (isActive && _signIn.value != null) {
                // Often enough that the picture reads as the page itself.
                delay(1_500)
                if (_signInBusy.value) continue
                attempt { show(client.chatSignInStep()) }
            }
        }
    }

    fun dismissError() { _error.value = null }

    private suspend fun attempt(block: suspend () -> Unit) {
        try {
            block()
            _error.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: "Kleinanzeigen could not be reached"
        }
    }
}
