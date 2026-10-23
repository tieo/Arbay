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
 * Every change to a draft is kept on the server a moment after the last key, so the phone, the
 * browser and an assistant see the same draft. A change to a posted ad goes to the market, so it
 * waits on screen until the user saves it there.
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
        if (rendersASample || draft.postedAdId != null) return
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

    /** Whether the open ad is posted and changed on screen since, which only saving puts on the market. */
    fun unsaved(draft: AdDraft): Boolean =
        draft.postedAdId != null && _drafts.value.firstOrNull { it.id == draft.id }?.let { it != draft } == true

    /** The open posted ad's changes saved on Kleinanzeigen. */
    fun save() {
        val draft = _open.value?.takeIf { it.postedAdId != null } ?: return
        if (rendersASample) return
        viewModelScope.launch {
            _busy.value = "Saving on Kleinanzeigen"
            attempt {
                val kept = client.updateAd(draft)
                _drafts.value = _drafts.value.map { if (it.id == kept.id) kept else it }
                if (_open.value == draft) _open.value = kept
            }
            _busy.value = null
        }
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

    /** The user's own [photos], added to the open draft in the order given. */
    fun addPhotos(photos: List<ByteArray>) {
        val id = _open.value?.id ?: return
        if (rendersASample || photos.isEmpty()) return
        viewModelScope.launch {
            _busy.value = "Adding ${if (photos.size == 1) "the photo" else "${photos.size} photos"}"
            attempt {
                photos.forEach { photo ->
                    val kept = client.addAdPhoto(id, photo)
                    _drafts.value = _drafts.value.map { if (it.id == kept.id) kept else it }
                    // Typed changes not kept yet stay; only the photos come from the server.
                    _open.value = _open.value?.takeIf { it.id == kept.id }?.copy(photos = kept.photos) ?: _open.value
                }
            }
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
