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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    private val _facetRemoved = MutableStateFlow<Map<String, Int>>(emptyMap())
    val facetRemoved: StateFlow<Map<String, Int>> = _facetRemoved

    private val _priceHistory = MutableStateFlow<List<Listing>>(emptyList())
    val priceHistory: StateFlow<List<Listing>> = _priceHistory

    private val _soldLoading = MutableStateFlow(false)
    val soldLoading: StateFlow<Boolean> = _soldLoading

    // Derived: listings filtered by selected platform, not banned
    val listings: StateFlow<List<Listing>> = combine(_allListings, _selectedPlatform, _bannedIds) { all, platform, banned ->
        val platformFiltered = if (platform == null) all else all.filter { it.platformId == platform }
        platformFiltered.filter { it.id !in banned }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            _facetRemoved.value = emptyMap()

            try {
                withTimeoutOrNull(360_000L) {
                client.crawlerSearchStream(query, platforms = platforms, filters = filters).collect { event ->
                    when (event.type) {
                        CrawlerEventType.SEARCH_STARTED -> {
                            _totalPlatforms.value = event.totalPlatforms
                        }

                        CrawlerEventType.PLATFORM_STARTED -> {
                            _platformStatuses.value = _platformStatuses.value + PlatformStatus(
                                platformId = event.platform,
                                platformName = event.platformName,
                                status = PlatformSearchStatus.SEARCHING,
                            )
                        }

                        CrawlerEventType.PLATFORM_DONE -> {
                            _completedPlatforms.value = event.completedPlatforms
                            _platformStatuses.value = _platformStatuses.value.map {
                                if (it.platformId == event.platform) it.copy(
                                    status = PlatformSearchStatus.DONE,
                                    resultCount = event.resultCount,
                                    rawCount = event.rawCount,
                                ) else it
                            }
                            _allListings.value = (_allListings.value + event.listings)
                                .sortedBy { DisplayCurrency.convert(it.effectivePrice.amount, it.effectivePrice.currency.name) }
                            if (event.facetRemoved.isNotEmpty()) {
                                _facetRemoved.value = (_facetRemoved.value.keys + event.facetRemoved.keys)
                                    .associateWith { (_facetRemoved.value[it] ?: 0) + (event.facetRemoved[it] ?: 0) }
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
                            _platformStatuses.value = _platformStatuses.value.map {
                                if (it.platformId == event.platform) it.copy(fetchStage = event.fetchStage)
                                else it
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
