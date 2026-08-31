package io.github.tieo.arbay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

class ProductViewModel(
    private val client: ArbayClient = ArbayClient(),
    // The gallery renders the views with no server to ask, and a Home with nothing saved shows an
    // empty screen that says nothing about what Home looks like in use.
    saved: List<TrackedProduct> = emptyList(),
    savedStatus: List<SavedSearchStatus> = emptyList(),
    // The states Home can be in while it has nothing to show: still asking the
    // server, and unable to reach it.
    sampleLoading: Boolean = false,
    sampleError: String? = null,
    // Handed nothing on purpose: an empty Home is a state to draw, not a Home
    // that has not asked yet.
    rendersASample: Boolean = false,
) : ViewModel() {

    private val _products = MutableStateFlow(saved)
    val products: StateFlow<List<TrackedProduct>> = _products

    private val _loading = MutableStateFlow(sampleLoading)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow(sampleError)
    val error: StateFlow<String?> = _error

    // Keyed by saved-search id: what it has found since it was last opened, and whether the server
    // is re-running it at all.
    private val _status = MutableStateFlow(savedStatus.associateBy { it.productId })
    val status: StateFlow<Map<String, SavedSearchStatus>> = _status

    private val rendersASample =
        rendersASample || saved.isNotEmpty() || sampleLoading || sampleError != null

    fun loadProducts() {
        if (rendersASample) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _products.value = client.getProducts()
                _status.value = try {
                    client.getSavedSearchStatus().associateBy { it.productId }
                } catch (_: Exception) {
                    emptyMap()
                }
            } catch (e: Exception) {
                _error.value = e.message
            }
            _loading.value = false
        }
    }

    fun createProduct(
        name: String,
        searchText: String,
        platforms: List<PlatformId>,
        category: MarketGroup,
        identifiers: ProductIdentifier = ProductIdentifier(),
        carFilters: io.github.tieo.arbay.model.CarFilters? = null,
        excludeKeywords: List<String> = emptyList(),
        aliases: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            try {
                val product = TrackedProduct(
                    id = generateId(),
                    name = name,
                    searchQuery = SearchQuery(
                        text = searchText,
                        platforms = platforms,
                        excludeKeywords = excludeKeywords,
                        category = category,
                        aliases = aliases,
                    ).withCarFilters(carFilters),
                    identifiers = identifiers,
                    createdAt = Clock.System.now(),
                )
                client.createProduct(product)
                loadProducts()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    fun deleteProduct(id: String) {
        viewModelScope.launch {
            try {
                client.deleteProduct(id)
                loadProducts()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    /** Persist an edited bookmark (name, query, platforms, blocked keywords, car filters). */
    fun updateProduct(product: TrackedProduct) {
        viewModelScope.launch {
            try {
                client.updateProduct(product)
                loadProducts()
            } catch (e: Exception) {
                _error.value = e.message
            }
        }
    }

    /** Set the blocked keywords on a bookmark and persist. */
    fun setBlockedKeywords(product: TrackedProduct, keywords: List<String>) {
        updateProduct(
            product.copy(searchQuery = product.searchQuery.copy(excludeKeywords = keywords)),
        )
    }

    private fun generateId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }

    /** A saved search was opened: what was waiting in it has been seen. */
    fun markOpened(productId: String) {
        _status.value = _status.value[productId]?.let { current ->
            _status.value + (productId to current.copy(newSinceOpened = 0))
        } ?: _status.value
        viewModelScope.launch { runCatching { client.markSavedSearchOpened(productId) } }
    }
}
