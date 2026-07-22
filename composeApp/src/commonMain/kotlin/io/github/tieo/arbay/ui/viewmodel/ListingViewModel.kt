package io.github.tieo.arbay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.loadBannedIds
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.saveBannedIds
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How the results list is ordered; chosen by the user from the sort menu. */
enum class SortMode(val label: String) {
    BEST_MATCH("Best match"),
    PRICE_ASC("Price: low to high"),
    PRICE_DESC("Price: high to low"),
    NEAREST("Nearest first"),
    NEWEST("Newest first"),
}

data class PlatformStatus(
    val platformId: String,
    val platformName: String,
    val status: PlatformSearchStatus,
    val resultCount: Int = 0,
    val rawCount: Int = 0,
    val error: String? = null,
    val errorType: String? = null,
    val captchaUrl: String? = null,
    val fetchStage: String? = null,
    // The translated term a cross-border market was searched with, when it differs from the query.
    val queryUsed: String? = null,
)

class ListingViewModel(
    private val client: ArbayClient = ArbayClient(),
) : ViewModel() {

    // All results from the search (unfiltered by platform)
    private val _allListings = MutableStateFlow<List<Listing>>(emptyList())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedPlatform = MutableStateFlow<PlatformId?>(null)
    val selectedPlatform: StateFlow<PlatformId?> = _selectedPlatform

    private val _platformStatuses = MutableStateFlow<List<PlatformStatus>>(emptyList())
    val platformStatuses: StateFlow<List<PlatformStatus>> = _platformStatuses

    private val _totalPlatforms = MutableStateFlow(0)
    val totalPlatforms: StateFlow<Int> = _totalPlatforms

    private val _completedPlatforms = MutableStateFlow(0)
    val completedPlatforms: StateFlow<Int> = _completedPlatforms

    private val _bannedIds = MutableStateFlow<Set<String>>(loadBannedIds())
    val bannedIds: StateFlow<Set<String>> = _bannedIds

    private val _priceHistory = MutableStateFlow<List<Listing>>(emptyList())
    val priceHistory: StateFlow<List<Listing>> = _priceHistory

    private val _soldLoading = MutableStateFlow(false)
    val soldLoading: StateFlow<Boolean> = _soldLoading

    // Per active car filter, how many more results dropping it would add — summed across platforms.
    private val _facets = MutableStateFlow<Map<String, Int>>(emptyMap())
    val facets: StateFlow<Map<String, Int>> = _facets

    // Per-bookmark blocked keywords: a listing whose title or description contains any of these
    // terms is hidden. Filtered client-side (never sent to a crawler's own search).
    private val _blockedTerms = MutableStateFlow<List<String>>(emptyList())
    val blockedTerms: StateFlow<List<String>> = _blockedTerms

    // Derived: listings filtered by selected platform, not banned, not matching a blocked keyword.
    val listings: StateFlow<List<Listing>> = combine(_allListings, _selectedPlatform, _bannedIds, _blockedTerms) { all, platform, banned, blocked ->
        val platformFiltered = if (platform == null) all else all.filter { it.platformId == platform }
        platformFiltered.filter { l ->
            l.id !in banned && run {
                if (blocked.isEmpty()) return@run true
                val hay = "${l.title} ${l.description ?: ""}".lowercase()
                blocked.none { it.isNotBlank() && hay.contains(it.lowercase()) }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Set the active blocked-keyword list (from the bookmark being viewed). */
    fun setBlockedTerms(terms: List<String>) { _blockedTerms.value = terms }

    /** Add a word/phrase to the block list (from a listing's Block button). Returns the new list. */
    fun blockTerm(term: String): List<String> {
        val t = term.trim()
        if (t.isNotEmpty() && _blockedTerms.value.none { it.equals(t, ignoreCase = true) }) {
            _blockedTerms.value = _blockedTerms.value + t
        }
        return _blockedTerms.value
    }

    /** Remove a blocked word/phrase (from its removable chip). Returns the new list. */
    fun unblockTerm(term: String): List<String> {
        _blockedTerms.value = _blockedTerms.value.filterNot { it.equals(term, ignoreCase = true) }
        return _blockedTerms.value
    }

    fun ban(listing: Listing) {
        val updated = _bannedIds.value + listing.id
        _bannedIds.value = updated
        saveBannedIds(updated)
    }

    private var searchJob: Job? = null
    private var carFilters: CarFilters? = null

    fun selectPlatform(platform: PlatformId?) {
        _selectedPlatform.value = platform
    }

    fun refresh(platforms: List<PlatformId>? = null) {
        val query = _searchQuery.value
        if (query.isBlank() || _loading.value) return
        _allListings.value = emptyList()
        search(query, platforms, carFilters, force = true)
    }

    fun searchSold() {
        val query = _searchQuery.value
        if (query.isBlank() || _soldLoading.value) return
        viewModelScope.launch {
            _soldLoading.value = true
            try {
                val history = try { client.getPriceHistory(query) } catch (_: Exception) { emptyList() }
                val ebayPlatforms = listOf(PlatformId.EBAY_DE, PlatformId.EBAY_COM)
                val freshSold = ebayPlatforms.flatMap { platform ->
                    try { client.crawlerSearch(query, platform, limit = 500, sold = true) } catch (_: Exception) { emptyList() }
                }
                val seen = mutableSetOf<String>()
                _priceHistory.value = (freshSold + history + _priceHistory.value)
                    .filter { it.sold && seen.add(it.id) }
                    .sortedByDescending { it.soldDate ?: it.scrapedAt }
            } catch (_: Exception) {
            } finally {
                _soldLoading.value = false
            }
        }
    }

    // Device position, sent so the server fills in each listing's distance; and whether to order the
    // results nearest-first.
    private var userLat: Double? = null
    private var userLon: Double? = null
    private val _sortMode = MutableStateFlow(SortMode.BEST_MATCH)
    val sortMode: StateFlow<SortMode> = _sortMode
    // Kept for the callers that only ask "are we nearest-first?".
    val sortByDistance: StateFlow<Boolean> = _sortMode
        .map { it == SortMode.NEAREST }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setLocation(lat: Double?, lon: Double?) { userLat = lat; userLon = lon }

    fun setSortByDistance(on: Boolean) {
        setSortMode(if (on) SortMode.NEAREST else SortMode.BEST_MATCH)
    }

    fun setSortMode(mode: SortMode) {
        _sortMode.value = mode
        _allListings.value = sortListings(_allListings.value)
    }

    private fun priceOf(listing: Listing): Long =
        DisplayCurrency.convert(listing.effectivePrice.amount, listing.effectivePrice.currency.name)

    /** Order results by the mode the user picked from the sort menu. */
    private fun sortListings(list: List<Listing>): List<Listing> = when (_sortMode.value) {
        SortMode.NEAREST -> list.sortedWith(
            compareBy<Listing> { it.distanceKm ?: Double.MAX_VALUE }.thenBy { priceOf(it) })
        SortMode.PRICE_ASC -> list.sortedBy { priceOf(it) }
        SortMode.PRICE_DESC -> list.sortedByDescending { priceOf(it) }
        SortMode.NEWEST -> list.sortedByDescending { it.soldDate ?: it.scrapedAt }
        SortMode.BEST_MATCH -> list.sortedWith(
            compareByDescending<Listing> { it.matchScore ?: Double.NEGATIVE_INFINITY }
                .thenBy { priceOf(it) })
    }

    fun search(query: String, platforms: List<PlatformId>? = null, filters: CarFilters? = null, force: Boolean = false) {
        if (query.isBlank()) return
        if (!force && query == _searchQuery.value && filters == carFilters && (_allListings.value.isNotEmpty() || _loading.value)) return
        _searchQuery.value = query
        carFilters = filters
        _priceHistory.value = emptyList()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _allListings.value = emptyList()
            _platformStatuses.value = emptyList()
            _completedPlatforms.value = 0
            _totalPlatforms.value = 0
            _facets.value = emptyMap()

            try {
                withTimeoutOrNull(360_000L) {
                client.crawlerSearchStream(query, platforms = platforms, filters = filters, lat = userLat, lon = userLon).collect { event ->
                    when (event.type) {
                        CrawlerEventType.SEARCH_STARTED -> {
                            _totalPlatforms.value = event.totalPlatforms
                        }

                        CrawlerEventType.PLATFORM_STARTED -> {
                            _platformStatuses.value = _platformStatuses.value + PlatformStatus(
                                platformId = event.platform,
                                platformName = event.platformName,
                                status = PlatformSearchStatus.SEARCHING,
                                queryUsed = event.queryUsed,
                            )
                        }

                        CrawlerEventType.PLATFORM_DONE -> {
                            _completedPlatforms.value = event.completedPlatforms
                            _platformStatuses.value = _platformStatuses.value.map {
                                if (it.platformId == event.platform) it.copy(
                                    status = PlatformSearchStatus.DONE,
                                    resultCount = event.resultCount,
                                    rawCount = event.rawCount,
                                    captchaUrl = null, // solved (or never needed) — drop the link
                                ) else it
                            }
                            // Reconcile: replace this platform's streamed preview listings with its
                            // authoritative detail-enriched set, so any preview item the final filter
                            // dropped disappears and enriched specs replace the card-only ones.
                            _allListings.value = sortListings(
                                (_allListings.value.filterNot { it.platformId.name == event.platform } + event.listings)
                                    .distinctBy { it.id })
                            if (event.facets.isNotEmpty()) {
                                _facets.value = (_facets.value.keys + event.facets.keys).associateWith { k ->
                                    (_facets.value[k] ?: 0) + (event.facets[k] ?: 0)
                                }
                            }
                        }

                        CrawlerEventType.PLATFORM_ERROR -> {
                            _completedPlatforms.value = event.completedPlatforms
                            val errorStatus = when (event.errorType) {
                                "CAPTCHA" -> PlatformSearchStatus.CAPTCHA
                                "TIMEOUT" -> PlatformSearchStatus.TIMEOUT
                                "BLOCKED_403", "AUTH_REQUIRED_401" -> PlatformSearchStatus.IP_BLOCKED
                                else -> PlatformSearchStatus.ERROR
                            }
                            _platformStatuses.value = _platformStatuses.value.map {
                                if (it.platformId == event.platform) it.copy(
                                    status = errorStatus,
                                    error = event.error,
                                    errorType = event.errorType,
                                    captchaUrl = event.captchaUrl,
                                ) else it
                            }
                        }

                        CrawlerEventType.PLATFORM_PROGRESS -> {
                            // A progress event either carries a fetch-stage label or a just-parsed
                            // page of listings (pipelined). Append the page live so results stream in
                            // rather than landing all at once when the platform finishes.
                            if (event.listings.isNotEmpty()) {
                                _allListings.value = sortListings(
                                    (_allListings.value + event.listings).distinctBy { it.id })
                            }
                            _platformStatuses.value = _platformStatuses.value.map {
                                if (it.platformId == event.platform) it.copy(fetchStage = event.fetchStage ?: it.fetchStage)
                                else it
                            }
                        }

                        CrawlerEventType.CAPTCHA_INTERACTIVE -> {
                            // The crawl exposed its live browser for a human to solve a captcha in
                            // place. Attach the (base-qualified) solve link to the platform, which is
                            // still running — it resumes once solved.
                            val full = event.captchaUrl?.let {
                                if (it.startsWith("http")) it else client.baseUrl + it
                            }
                            _platformStatuses.value = _platformStatuses.value.map {
                                if (it.platformId == event.platform) it.copy(captchaUrl = full) else it
                            }
                        }

                        CrawlerEventType.SEARCH_COMPLETE -> {}
                    }
                }
                } // withTimeoutOrNull
            } catch (e: Exception) {
                if (_allListings.value.isEmpty()) {
                    try {
                        val results = client.crawlerSearch(query, null)
                        _allListings.value = results
                    } catch (e2: Exception) {
                        try {
                            _allListings.value = client.searchListings(query)
                        } catch (e3: Exception) {
                            _error.value = e3.message
                        }
                    }
                }
            } finally {
                _loading.value = false
                // Mark any still-searching platforms as timed out
                _platformStatuses.value = _platformStatuses.value.map {
                    if (it.status == PlatformSearchStatus.SEARCHING)
                        it.copy(status = PlatformSearchStatus.ERROR, error = "Timeout")
                    else it
                }
            }
        }
    }
}
