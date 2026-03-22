package io.github.tieo.arbay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ListingViewModel(
    private val client: ArbayClient = ArbayClient(),
) : ViewModel() {

    private val _listings = MutableStateFlow<List<Listing>>(emptyList())
    val listings: StateFlow<List<Listing>> = _listings

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _selectedPlatform = MutableStateFlow<PlatformId?>(null)
    val selectedPlatform: StateFlow<PlatformId?> = _selectedPlatform

    fun search(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            loadListings()
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _listings.value = client.searchListings(query)
            } catch (e: Exception) {
                _error.value = e.message
            }
            _loading.value = false
        }
    }

    fun loadListings(platform: PlatformId? = _selectedPlatform.value) {
        _selectedPlatform.value = platform
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _listings.value = client.getListings(platform = platform)
            } catch (e: Exception) {
                _error.value = e.message
            }
            _loading.value = false
        }
    }
}
