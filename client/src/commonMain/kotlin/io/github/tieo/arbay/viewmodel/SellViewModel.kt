package io.github.tieo.arbay.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.AdDraft
import io.github.tieo.arbay.model.photoPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The user's ads for sale, shared by the phone and the web: drafts made from ads the user bought
 * from, changed here, and posted on Kleinanzeigen only when the user presses post.
 *
 * Every change is kept on the server a moment after the last key, so the phone, the browser and an
 * assistant see the same draft.
 */
class SellViewModel(
    private val client: ArbayClient = ArbayClient(),
    sampleDrafts: List<AdDraft> = emptyList(),
    /** An off-screen render, which never reaches the server. */
    private val rendersASample: Boolean = sampleDrafts.isNotEmpty(),
) : ViewModel() {

    private val _drafts = MutableStateFlow(sampleDrafts)
    val drafts: StateFlow<List<AdDraft>> = _drafts.asStateFlow()

    private val _open = MutableStateFlow(sampleDrafts.firstOrNull())
    /** The draft on screen, with the user's changes as they type them. */
    val open: StateFlow<AdDraft?> = _open.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    /** What is going on that takes a while: making a draft, posting one. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun load() {
        if (rendersASample) return
        viewModelScope.launch { attempt { _drafts.value = client.adDrafts() } }
    }

    /** A draft like the ad behind [listingId]; [opened] gets its id once it is made. */
    fun startFrom(listingId: String, opened: (String) -> Unit) {
        if (rendersASample) return
        viewModelScope.launch {
            _busy.value = "Copying the ad and its photos"
            attempt {
                val draft = client.newAdDraft(listingId)
                _drafts.value = listOf(draft) + _drafts.value
                _open.value = draft
                opened(draft.id)
            }
            _busy.value = null
        }
    }

    fun open(id: String) {
        _drafts.value.firstOrNull { it.id == id }?.let { if (_open.value?.id != id) _open.value = it }
        if (rendersASample) return
        viewModelScope.launch {
            attempt {
                val all = client.adDrafts()
                _drafts.value = all
                // Changes typed but not kept yet stay on screen.
                if (saving?.isActive != true) _open.value = all.firstOrNull { it.id == id }
            }
        }
    }

    private var saving: Job? = null

    /** The draft as the user changed it; kept on the server a moment after the last change. */
    fun change(draft: AdDraft) {
        _open.value = draft
        if (rendersASample) return
        saving?.cancel()
        saving = viewModelScope.launch {
            delay(600)
            keep(draft)
        }
    }

    private suspend fun keep(draft: AdDraft) = attempt {
        val kept = client.changeAdDraft(draft)
        _drafts.value = _drafts.value.map { if (it.id == kept.id) kept else it }
    }

    fun post(done: (AdDraft) -> Unit = {}) {
        val draft = _open.value ?: return
        if (rendersASample) return
        viewModelScope.launch {
            saving?.cancel()
            _busy.value = "Posting on Kleinanzeigen"
            var refused: String? = null
            attempt {
                client.changeAdDraft(draft)
                val result = client.postAd(draft.id)
                _open.value = result.draft
                _drafts.value = _drafts.value.map { if (it.id == result.draft.id) result.draft else it }
                refused = result.error
                if (refused == null) done(result.draft)
            }
            refused?.let { _error.value = it }
            _busy.value = null
        }
    }

    fun delete(id: String, done: () -> Unit = {}) {
        if (rendersASample) return
        viewModelScope.launch {
            saving?.cancel()
            attempt {
                client.deleteAdDraft(id)
                _drafts.value = _drafts.value.filterNot { it.id == id }
                if (_open.value?.id == id) _open.value = null
                done()
            }
        }
    }

    fun photoAddress(draft: AdDraft, photo: String): String =
        client.address(draft.photoPath(photo))

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
