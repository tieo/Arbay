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
) : ViewModel() {

    private val _products = MutableStateFlow<List<TrackedProduct>>(emptyList())
    val products: StateFlow<List<TrackedProduct>> = _products

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun loadProducts() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                _products.value = client.getProducts()
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
        identifiers: ProductIdentifier = ProductIdentifier(),
    ) {
        viewModelScope.launch {
            try {
                val product = TrackedProduct(
                    id = generateId(),
                    name = name,
                    searchQuery = SearchQuery(
                        text = searchText,
                        platforms = platforms,
                    ),
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

    fun toggleProduct(id: String) {
        viewModelScope.launch {
            try {
                val product = _products.value.find { it.id == id } ?: return@launch
                client.updateProduct(product.copy(active = !product.active))
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

    private fun generateId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..12).map { chars.random() }.joinToString("")
    }
}
