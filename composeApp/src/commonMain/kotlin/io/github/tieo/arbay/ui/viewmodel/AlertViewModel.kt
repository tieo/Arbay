package io.github.tieo.arbay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.Alert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class AlertViewModel(
    private val client: ArbayClient = ArbayClient(),
) : ViewModel() {

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts

    val unreadCount: StateFlow<Int> = _alerts
        .map { list -> list.count { !it.read } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun loadAlerts(unreadOnly: Boolean = false) {
        viewModelScope.launch {
            _loading.value = true
            try {
                _alerts.value = client.getAlerts(unreadOnly)
            } catch (_: Exception) {}
            _loading.value = false
        }
    }

    fun markRead(id: String) {
        viewModelScope.launch {
            try {
                client.markAlertRead(id)
                loadAlerts()
            } catch (_: Exception) {}
        }
    }

    fun markAllRead() {
        viewModelScope.launch {
            try {
                client.markAllAlertsRead()
                loadAlerts()
            } catch (_: Exception) {}
        }
    }
}
