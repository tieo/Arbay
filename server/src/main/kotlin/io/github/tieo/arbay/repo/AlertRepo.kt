package io.github.tieo.arbay.repo

import io.github.tieo.arbay.model.Alert
import java.util.concurrent.ConcurrentHashMap

class AlertRepo {
    private val alerts = ConcurrentHashMap<String, Alert>()

    fun getAll(unreadOnly: Boolean = false, limit: Int = 50): List<Alert> {
        return alerts.values
            .asSequence()
            .filter { !unreadOnly || !it.read }
            .sortedByDescending { it.createdAt }
            .take(limit)
            .toList()
    }

    fun getByProductId(productId: String): List<Alert> {
        return alerts.values.filter { it.productId == productId }.sortedByDescending { it.createdAt }
    }

    fun create(alert: Alert): Alert {
        alerts[alert.id] = alert
        return alert
    }

    fun markRead(id: String): Alert? {
        val alert = alerts[id] ?: return null
        val updated = alert.copy(read = true)
        alerts[id] = updated
        return updated
    }

    fun markAllRead(): Int {
        var count = 0
        alerts.replaceAll { _, alert ->
            if (!alert.read) {
                count++
                alert.copy(read = true)
            } else alert
        }
        return count
    }
}
