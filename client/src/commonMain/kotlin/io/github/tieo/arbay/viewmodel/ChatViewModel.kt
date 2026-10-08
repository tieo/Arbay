package io.github.tieo.arbay.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.BlockReview
import io.github.tieo.arbay.model.ChatAccount
import io.github.tieo.arbay.model.ChatSettings
import io.github.tieo.arbay.model.Conversation
import io.github.tieo.arbay.model.MessageTemplate
import io.github.tieo.arbay.model.OutgoingMessage
import io.github.tieo.arbay.model.OutgoingState
import io.github.tieo.arbay.model.SendRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
                _outbox.value = client.outbox()
                if (account.signedIn) _conversations.value = client.conversations()
            }
            _loading.value = false
        }
    }

    private fun refreshSettings() = viewModelScope.launch { attempt { _settings.value = client.chatSettings() } }

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

    fun deleteTemplate(id: String) = updateSettings { s -> s.copy(templates = s.templates.filterNot { it.id == id }) }

    fun newTemplateId(): String = "t${Clock.System.now().toEpochMilliseconds()}"

    private fun updateSettings(change: (ChatSettings) -> ChatSettings) {
        val new = change(_settings.value)
        _settings.value = new
        if (rendersASample) return
        viewModelScope.launch { attempt { _settings.value = client.updateChatSettings(new) } }
    }

    fun beginSignIn() = viewModelScope.launch { attempt { _account.value = client.beginChatSignIn() } }

    fun endSignIn() = viewModelScope.launch {
        attempt {
            _account.value = client.endChatSignIn()
            refresh()
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
