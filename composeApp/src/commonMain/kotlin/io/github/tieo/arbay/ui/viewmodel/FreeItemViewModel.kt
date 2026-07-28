package io.github.tieo.arbay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.showMatchNotification
import io.github.tieo.arbay.model.PlatformSearchStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class FreeItemViewModel(
    private val client: ArbayClient = ArbayClient(),
    // A profile handed in rather than fetched, so the renderer draws Home as it
    // looks in use: with the Free Items card the real app pins at the top.
    sampleProfile: FreeItemProfile? = null,
    sampleItems: List<Listing> = emptyList(),
    sampleLoading: Boolean = false,
    sampleError: String? = null,
    rendersASample: Boolean = false,
) : ViewModel() {

    private val rendersASample = rendersASample ||
        sampleProfile != null || sampleItems.isNotEmpty() || sampleLoading || sampleError != null

    // ── Discover tab ──────────────────────────────────────────────────────────
    private val _listings = MutableStateFlow(sampleItems)
    val listings: StateFlow<List<Listing>> = _listings

    private val _loading = MutableStateFlow(sampleLoading)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow(sampleError)
    val error: StateFlow<String?> = _error

    private val _platformStatus = MutableStateFlow<PlatformStatus?>(null)
    val platformStatus: StateFlow<PlatformStatus?> = _platformStatus

    // IDs of listings the user has acted on (hide from card stack)
    private val _dismissedIds = MutableStateFlow<Set<String>>(emptySet())
    val dismissedIds: StateFlow<Set<String>> = _dismissedIds

    // ── Undo support ─────────────────────────────────────────────────────────
    data class UndoableAction(val listing: Listing, val action: FeedbackAction, val direction: String = "left")
    private val _lastAction = MutableStateFlow<UndoableAction?>(null)
    val lastAction: StateFlow<UndoableAction?> = _lastAction

    // IDs that were undone — protected from being re-dismissed by loadInsights
    private val _undoneIds = mutableSetOf<String>()

    // Direction to animate the card sliding back from (set on undo, cleared after animation)
    private val _undoAnimDirection = MutableStateFlow<String?>(null)
    val undoAnimDirection: StateFlow<String?> = _undoAnimDirection

    // ── Infinite pagination state ────────────────────────────────────────────
    private var _nextPage = 1
    private var _hasMore = true
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore

    private val _currentRadiusKm = MutableStateFlow<Int?>(null)
    val currentRadiusKm: StateFlow<Int?> = _currentRadiusKm

    // ── Telemetry ─────────────────────────────────────────────────────────────
    private val _telemetry = MutableStateFlow<List<TelemetryEvent>>(emptyList())
    val telemetry: StateFlow<List<TelemetryEvent>> = _telemetry

    // ── Profile & insights ────────────────────────────────────────────────────
    private val _profile = MutableStateFlow(sampleProfile)
    val profile: StateFlow<FreeItemProfile?> = _profile

    private val _insights = MutableStateFlow<FreeItemInsights?>(null)
    val insights: StateFlow<FreeItemInsights?> = _insights

    // ── History tab ───────────────────────────────────────────────────────────
    private val _history = MutableStateFlow<List<FeedbackHistoryItem>>(emptyList())
    val history: StateFlow<List<FeedbackHistoryItem>> = _history

    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading

    // ── Stats tab ─────────────────────────────────────────────────────────────
    private val _stats = MutableStateFlow<FreeItemStats?>(null)
    val stats: StateFlow<FreeItemStats?> = _stats

    // ── New matches from background tracking ─────────────────────────────────
    private val _newMatches = MutableStateFlow<List<NewMatch>>(emptyList())
    val newMatches: StateFlow<List<NewMatch>> = _newMatches

    // ── Model Arena ──────────────────────────────────────────────────────────
    private val _models = MutableStateFlow<List<ArbayClient.ModelInfo>>(emptyList())
    val models: StateFlow<List<ArbayClient.ModelInfo>> = _models

    private val _arenaLeaderboard = MutableStateFlow<List<ArbayClient.ArenaEntry>>(emptyList())
    val arenaLeaderboard: StateFlow<List<ArbayClient.ArenaEntry>> = _arenaLeaderboard

    // ── Rejected items (false negative browser) ──────────────────────────────
    private val _rejectedItems = MutableStateFlow<List<RejectedItem>>(emptyList())
    val rejectedItems: StateFlow<List<RejectedItem>> = _rejectedItems

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private var matchPollJob: Job? = null

    init {
        loadProfile()
        loadInsights()
        loadHistory()
        loadStats()
        loadModels()
        startMatchPolling()
    }

    fun loadProfile() {
        if (rendersASample) return
        viewModelScope.launch {
            try {
                _profile.value = client.getFreeItemProfile()
            } catch (_: Exception) {}
        }
    }

    /** Drop a stale error banner — e.g. when the guided profile editor is showing instead. */
    fun clearError() { _error.value = null }

    fun saveProfile(description: String, location: String? = null, radiusKm: Int? = null, trackingEnabled: Boolean? = null) {
        viewModelScope.launch {
            try {
                val effectiveRadius = radiusKm ?: _profile.value?.radiusKm ?: 30
                val effectiveTracking = trackingEnabled ?: _profile.value?.trackingEnabled ?: false
                client.setFreeItemProfile(description, location, effectiveRadius, effectiveTracking)
                _profile.value = FreeItemProfile(description = description, location = location, radiusKm = effectiveRadius, trackingEnabled = effectiveTracking)
                logTelemetry("PROFILE_UPDATED", "radius=${effectiveRadius}km location=$location tracking=$effectiveTracking")
            } catch (e: Exception) {
                _error.value = "Could not save profile: ${e.message}"
            }
        }
    }

    fun toggleTracking(enabled: Boolean) {
        val p = _profile.value ?: return
        saveProfile(p.description, p.location, p.radiusKm, trackingEnabled = enabled)
    }

    fun loadInsights() {
        viewModelScope.launch {
            try {
                val insights = client.getFreeItemInsights()
                _insights.value = insights
                // Pre-load seenIds so previously-swiped items don't reappear
                // But exclude IDs that were just undone
                if (insights != null && insights.seenIds.isNotEmpty()) {
                    val idsToAdd = insights.seenIds.filter { it !in _undoneIds }
                    _dismissedIds.value = _dismissedIds.value + idsToAdd
                }
            } catch (_: Exception) {}
        }
    }

    fun loadHistory() {
        _historyLoading.value = true
        viewModelScope.launch {
            try {
                _history.value = client.getFreeItemHistory()
            } catch (_: Exception) {}
            _historyLoading.value = false
        }
    }

    fun loadStats() {
        viewModelScope.launch {
            try {
                _stats.value = client.getFreeItemStats()
            } catch (_: Exception) {}
        }
    }

    /** Fresh search — clears everything and starts from page 1 */
    fun search(query: String = "") {
        searchJob?.cancel()
        loadMoreJob?.cancel()
        _listings.value = emptyList()
        _error.value = null
        _loading.value = true
        _platformStatus.value = null
        _nextPage = 1
        _hasMore = true
        _currentRadiusKm.value = null

        logTelemetry("SEARCH_STARTED", "query=$query")

        searchJob = viewModelScope.launch {
            fetchBatch(query, startPage = 1, append = false)
            // Immediately start loading more if first batch is small
            val visible = _listings.value.count { it.id !in _dismissedIds.value }
            if (visible < 10 && _hasMore) {
                logTelemetry("AUTO_PREFETCH", "visible=$visible nextPage=$_nextPage")
                _loadingMore.value = true
                fetchBatch(query, startPage = _nextPage, append = true)
                _loadingMore.value = false
            }
        }
    }

    /** Load more items — fetches the next batch and appends to existing listings */
    fun loadMore(query: String = "") {
        if (_loadingMore.value || !_hasMore || _loading.value) return
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            _loadingMore.value = true
            logTelemetry("LOAD_MORE", "startPage=$_nextPage")
            fetchBatch(query, startPage = _nextPage, append = true)
            _loadingMore.value = false
        }
    }

    /** Called when user is viewing card at index — triggers prefetch if nearing end */
    fun onCardViewed(visibleCount: Int, currentIndex: Int) {
        val remaining = visibleCount - currentIndex
        // Prefetch when 8 or fewer cards remain ahead
        if (remaining <= 8 && _hasMore && !_loadingMore.value && !_loading.value) {
            loadMore()
        }
    }

    /** Sort the deck by fit, but keep the card the user is currently on pinned at the front —
     *  a re-sort must never swap the card out from under them. */
    private fun deckSortedPinningCurrent(list: List<Listing>): List<Listing> {
        val dismissed = _dismissedIds.value
        val currentId = _listings.value.firstOrNull { it.id !in dismissed }?.id
        val sorted = list.sortedByDescending { it.relevanceScore ?: 0.0 }
        val current = sorted.firstOrNull { it.id == currentId }
        return if (current != null) listOf(current) + sorted.filter { it.id != currentId } else sorted
    }

    private suspend fun fetchBatch(query: String, startPage: Int, append: Boolean) {
        try {
            val radiusKm = _currentRadiusKm.value
            client.freeItemsStream(
                query = query,
                startPage = startPage,
                radiusKm = radiusKm,
            ).collect { event ->
                when (event.type) {
                    CrawlerEventType.PLATFORM_STARTED -> {
                        _platformStatus.value = PlatformStatus(
                            platformId = event.platform,
                            platformName = event.platformName.ifBlank { event.platform },
                            status = PlatformSearchStatus.SEARCHING,
                        )
                    }
                    CrawlerEventType.PLATFORM_PROGRESS -> {
                        _platformStatus.value = _platformStatus.value?.copy(
                            status = PlatformSearchStatus.SEARCHING,
                            fetchStage = event.fetchStage,
                            resultCount = event.resultCount,
                            rawCount = event.rawCount,
                        )
                        // Incrementally add listings as they arrive per page, sorted by relevance.
                        // The card in view stays pinned so a late-arriving match never swaps it out.
                        if (event.listings.isNotEmpty()) {
                            val existing = _listings.value.map { it.id }.toSet()
                            val newItems = event.listings.filter { it.id !in existing }
                            if (newItems.isNotEmpty()) {
                                _listings.value = deckSortedPinningCurrent(_listings.value + newItems)
                                // First cards in — drop the blocking spinner so the deck is swipeable
                                // immediately while the remaining pages keep streaming in behind it.
                                _loading.value = false
                            }
                        }
                    }
                    CrawlerEventType.PLATFORM_DONE -> {
                        _platformStatus.value = _platformStatus.value?.copy(
                            status = PlatformSearchStatus.DONE,
                            resultCount = event.resultCount,
                            rawCount = event.rawCount,
                        )
                        if (append) {
                            // Merge the new page and re-sort by fit — but keep the current card pinned,
                            // so a later page's strong match ranks the rest without swapping what's in view.
                            val existing = _listings.value.map { it.id }.toSet()
                            val newItems = event.listings.filter { it.id !in existing }
                            _listings.value = deckSortedPinningCurrent(_listings.value + newItems)
                            logTelemetry("BATCH_APPENDED", "new=${newItems.size} total=${_listings.value.size} page=$startPage")
                        } else {
                            _listings.value = deckSortedPinningCurrent(event.listings)
                            logTelemetry("BATCH_LOADED", "count=${event.listings.size} page=$startPage hasMore=${event.hasMore}")
                        }
                        _hasMore = event.hasMore
                        _nextPage = event.nextPage
                        _loading.value = false

                        // If very few visible results and we can expand radius
                        val visibleCount = _listings.value.count { it.id !in _dismissedIds.value }
                        if (visibleCount < 3 && !_hasMore) {
                            expandRadius()
                        }
                    }
                    CrawlerEventType.PLATFORM_ERROR -> {
                        _platformStatus.value = _platformStatus.value?.copy(
                            status = PlatformSearchStatus.ERROR,
                            error = event.error,
                            errorType = event.errorType,
                        )
                        _error.value = event.error
                        _loading.value = false
                        logTelemetry("ERROR", event.error ?: "unknown")
                    }
                    CrawlerEventType.SEARCH_COMPLETE -> {
                        _loading.value = false
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            _error.value = e.message ?: "Search failed"
            _loading.value = false
            logTelemetry("EXCEPTION", e.message ?: "unknown")
        }
    }

    /** Auto-expand radius when items run out */
    private fun expandRadius() {
        val currentRadius = _currentRadiusKm.value ?: _profile.value?.radiusKm ?: 30
        val nextRadius = when {
            currentRadius < 50 -> 50
            currentRadius < 100 -> 100
            currentRadius < 200 -> 200
            currentRadius < 500 -> 500
            else -> return
        }
        logTelemetry("RADIUS_EXPANDED", "$currentRadius → $nextRadius km")
        _currentRadiusKm.value = nextRadius
        _nextPage = 1
        _hasMore = true
        loadMore()
    }

    /** Send feedback, retrain server-side, rescore remaining items, and resort the feed.
     *  The current card (first visible) stays in place; only the queue behind it changes. */
    private fun sendFeedbackAndRescore(listing: Listing, action: FeedbackAction, direction: String = "left") {
        _lastAction.value = UndoableAction(listing, action, direction)
        dismiss(listing.id)
        val remainingIds = _listings.value
            .filter { it.id !in _dismissedIds.value && it.id != listing.id }
            .map { it.id }
        viewModelScope.launch {
            try {
                val response = client.submitFreeItemFeedback(
                    listingId = listing.id, title = listing.title, action = action,
                    url = listing.url,
                    imageUrl = listing.imageUrls.firstOrNull { it.startsWith("http") },
                    locationText = listing.location?.let { loc ->
                        loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
                    },
                    description = listing.description,
                    relevanceScore = listing.relevanceScore,
                    remainingIds = remainingIds,
                )
                // Apply rescored results — update scores and resort, pinning current card
                val rescored = response?.rescored
                if (!rescored.isNullOrEmpty()) {
                    val dismissed = _dismissedIds.value
                    val currentCardId = _listings.value.firstOrNull { it.id !in dismissed }?.id
                    _listings.value = _listings.value.map { item ->
                        rescored[item.id]?.let { newScore -> item.copy(relevanceScore = newScore) } ?: item
                    }.let { updated ->
                        // Pin current card at front, sort the rest by score
                        val current = updated.firstOrNull { it.id == currentCardId }
                        val rest = updated.filter { it.id != currentCardId }
                            .sortedByDescending { it.relevanceScore ?: 0.0 }
                        if (current != null) listOf(current) + rest else rest
                    }
                }
                if (action == FeedbackAction.LOVE || action == FeedbackAction.DISLIKE) {
                    refreshAfterFeedback()
                }
            } catch (_: Exception) {}
        }
    }

    fun love(listing: Listing) {
        logTelemetry("LOVE", "id=${listing.id} title=${listing.title.take(50)} score=${listing.relevanceScore}")
        sendFeedbackAndRescore(listing, FeedbackAction.LOVE, direction = "right")
    }

    fun pass(listing: Listing) {
        logTelemetry("PASS", "id=${listing.id} title=${listing.title.take(50)} score=${listing.relevanceScore}")
        sendFeedbackAndRescore(listing, FeedbackAction.PASS, direction = "left")
    }

    /** Like — positive signal to the model but doesn't save the item */
    fun like(listing: Listing) {
        logTelemetry("LIKE", "id=${listing.id} title=${listing.title.take(50)} score=${listing.relevanceScore}")
        sendFeedbackAndRescore(listing, FeedbackAction.LIKE, direction = "down")
    }

    fun dislike(listing: Listing) {
        logTelemetry("DISLIKE", "id=${listing.id} title=${listing.title.take(50)} score=${listing.relevanceScore}")
        sendFeedbackAndRescore(listing, FeedbackAction.DISLIKE, direction = "left")
    }

    /** "More like this" — saves the item (teaching the scorer what you like)
     *  and refreshes the search so similar items get boosted to the top. */
    fun moreLikeThis(listing: Listing) {
        logTelemetry("MORE_LIKE_THIS", "id=${listing.id} title=${listing.title.take(50)}")
        love(listing) // Save it as a positive signal — scorer will boost similar embeddings
        // Re-search to re-score all items with the new love signal
        search()
    }

    fun undoFeedback(listingId: String) {
        logTelemetry("UNDO", "id=$listingId")
        viewModelScope.launch {
            try {
                client.undoFreeItemFeedback(listingId)
                _dismissedIds.value = _dismissedIds.value - listingId
                refreshAfterFeedback()
            } catch (_: Exception) {}
        }
    }

    /** Undo the last swipe action — brings the card back, reverses server feedback, and retrains. */
    fun undoLast() {
        val undoAction = _lastAction.value ?: return
        _lastAction.value = null
        val id = undoAction.listing.id
        logTelemetry("UNDO_LAST", "id=$id was=${undoAction.action}")
        // Protect this ID from being re-dismissed by concurrent loadInsights calls
        _undoneIds.add(id)
        // Trigger slide-back animation from the direction the card was swiped
        _undoAnimDirection.value = undoAction.direction
        // Move the undone card to the front of the list so it becomes the visible card
        _listings.value = listOf(undoAction.listing) + _listings.value.filter { it.id != id }
        // Immediately bring card back into view
        _dismissedIds.value = _dismissedIds.value - id
        // Reverse server-side feedback and retrain from scratch without the removed example
        viewModelScope.launch {
            try {
                client.undoFreeItemFeedback(id)
                refreshAfterFeedback()
            } catch (_: Exception) {}
            // After server has removed the feedback, unprotect so future sessions work normally
            _undoneIds.remove(id)
        }
    }

    fun clearUndoAnimation() {
        _undoAnimDirection.value = null
    }

    /** Poll for new matches from background tracking every 60s. */
    private fun startMatchPolling() {
        matchPollJob?.cancel()
        matchPollJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000L) // Check every 60 seconds
                try {
                    val matches = client.getNewMatches()
                    if (matches.isNotEmpty()) {
                        _newMatches.value = _newMatches.value + matches
                        logTelemetry("NEW_MATCHES", "count=${matches.size}")
                        // Show local notification
                        val title = if (matches.size == 1) "New free item match!" else "${matches.size} new matches!"
                        val body = matches.first().title
                        showMatchNotification(title, body)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ── Model Arena methods ─────────────────────────────────────────────────

    fun loadModels() {
        viewModelScope.launch {
            try {
                _models.value = client.getModels()
                _arenaLeaderboard.value = client.getArenaLeaderboard()
            } catch (_: Exception) {}
        }
    }

    fun setActiveModel(modelId: String) {
        viewModelScope.launch {
            try {
                client.setActiveModel(modelId)
                loadModels()
                logTelemetry("MODEL_CHANGED", "modelId=$modelId")
            } catch (e: Exception) {
                _error.value = "Could not change model: ${e.message}"
            }
        }
    }

    fun retrainModels() {
        viewModelScope.launch {
            try {
                client.retrainModels()
                loadModels()
                logTelemetry("MODELS_RETRAINED", "")
            } catch (e: Exception) {
                _error.value = "Could not retrain: ${e.message}"
            }
        }
    }

    fun loadRejectedItems() {
        viewModelScope.launch {
            try {
                _rejectedItems.value = client.getRejectedItems()
            } catch (_: Exception) {}
        }
    }

    fun dismissNewMatches() {
        _newMatches.value = emptyList()
    }

    private fun refreshAfterFeedback() {
        loadInsights()
        loadHistory()
        loadStats()
    }

    private fun dismiss(id: String) {
        _dismissedIds.value = _dismissedIds.value + id
        // Check if we need to prefetch more items after dismissal
        val visibleCount = _listings.value.count { it.id !in _dismissedIds.value }
        onCardViewed(visibleCount, 0)
    }

    // ── Telemetry ─────────────────────────────────────────────────────────────

    private fun logTelemetry(action: String, detail: String) {
        val event = TelemetryEvent(
            timestamp = kotlinx.datetime.Clock.System.now(),
            action = action,
            detail = detail,
        )
        _telemetry.value = (_telemetry.value + event).takeLast(500) // Keep last 500 events
    }
}

data class TelemetryEvent(
    val timestamp: kotlinx.datetime.Instant,
    val action: String,
    val detail: String,
)
