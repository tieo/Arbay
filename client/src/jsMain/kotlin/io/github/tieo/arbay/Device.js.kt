package io.github.tieo.arbay

import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.notifications.GRANTED
import org.w3c.notifications.Notification
import org.w3c.notifications.NotificationOptions
import org.w3c.notifications.NotificationPermission

actual fun openBrowser(url: String) {
    window.open(url, "_blank", "noopener")
}

// The browser's local storage for this site holds what the phone keeps in its files, under the
// same names and in the same formats.
private fun readText(name: String): String? = localStorage.getItem("arbay.$name")
private fun writeText(name: String, text: String) = localStorage.setItem("arbay.$name", text)

actual fun loadBannedIds(): Set<String> =
    readText("banned_ids")?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

actual fun saveBannedIds(ids: Set<String>) = writeText("banned_ids", ids.joinToString("\n"))

actual fun loadSearchHistory(): String = readText("search_history") ?: ""

actual fun saveSearchHistory(json: String) = writeText("search_history", json)

actual fun loadDeviceSettings(): Map<String, String> =
    readText("settings")?.lines()?.mapNotNull { line ->
        line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }?.toMap() ?: emptyMap()

actual fun saveDeviceSettings(settings: Map<String, String>) =
    writeText("settings", settings.entries.joinToString("\n") { "${it.key}=${it.value}" })

/** Shown by the browser while the page is open, once the site may show notifications. */
actual fun showMatchNotification(title: String, body: String) {
    if (Notification.permission == NotificationPermission.GRANTED) {
        Notification(title, NotificationOptions(body = body))
    }
}
