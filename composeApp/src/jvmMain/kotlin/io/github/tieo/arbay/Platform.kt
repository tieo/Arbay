package io.github.tieo.arbay

import androidx.compose.runtime.Composable
import java.awt.Desktop
import java.io.File
import java.net.URI

actual fun openBrowser(url: String) {
    try {
        Desktop.getDesktop().browse(URI(url))
    } catch (_: Exception) {}
}

private val bannedFile = File(System.getProperty("user.home"), ".arbay/banned_ids.txt")

actual fun loadBannedIds(): Set<String> {
    return try {
        if (!bannedFile.exists()) return emptySet()
        bannedFile.readLines().filter { it.isNotBlank() }.toSet()
    } catch (_: Exception) { emptySet() }
}

actual fun saveBannedIds(ids: Set<String>) {
    try {
        bannedFile.parentFile.mkdirs()
        bannedFile.writeText(ids.joinToString("\n"))
    } catch (_: Exception) {}
}

private val searchHistoryFile = File(System.getProperty("user.home"), ".arbay/search_history.json")

actual fun loadSearchHistory(): String = try {
    if (searchHistoryFile.exists()) searchHistoryFile.readText() else ""
} catch (_: Exception) { "" }

actual fun saveSearchHistory(json: String) {
    try {
        searchHistoryFile.parentFile.mkdirs()
        searchHistoryFile.writeText(json)
    } catch (_: Exception) {}
}

actual fun imageModel(address: String): Any =
    if (address.startsWith("http")) address
    else File(address.removePrefix("file://"))

private val settingsFile = File(System.getProperty("user.home"), ".arbay/settings.txt")

actual fun loadDeviceSettings(): Map<String, String> = try {
    if (!settingsFile.exists()) emptyMap()
    else settingsFile.readLines().mapNotNull { line ->
        line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }.toMap()
} catch (_: Exception) { emptyMap() }

actual fun saveDeviceSettings(settings: Map<String, String>) {
    try {
        settingsFile.parentFile.mkdirs()
        settingsFile.writeText(settings.entries.joinToString("\n") { "${it.key}=${it.value}" })
    } catch (_: Exception) {}
}

actual fun showMatchNotification(title: String, body: String) {}
actual fun schedulePolling(intervalMinutes: Int) {}
actual fun cancelPolling() {}

@androidx.compose.runtime.Composable
actual fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit = { onCity(null) }

@androidx.compose.runtime.Composable
actual fun rememberCoordDetector(onCoords: (Double?, Double?) -> Unit): () -> Unit = { onCoords(null, null) }

@Composable
actual fun ReadPositionIfAllowed(onCoords: (Double?, Double?) -> Unit) { }
