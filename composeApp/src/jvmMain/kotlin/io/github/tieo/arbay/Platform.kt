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

private val dataDir = File(System.getProperty("user.home"), ".arbay")

// Each write lands whole and a failed one says so, the same as on Android.
private fun writeText(name: String, text: String) {
    dataDir.mkdirs()
    val target = File(dataDir, name)
    val tmp = File.createTempFile("$name.", ".tmp", dataDir)
    try {
        tmp.writeText(text)
        java.nio.file.Files.move(
            tmp.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    } finally {
        tmp.delete()
    }
}

private fun readText(name: String): String? =
    try { File(dataDir, name).takeIf { it.exists() }?.readText() } catch (_: java.io.IOException) { null }

actual fun loadBannedIds(): Set<String> =
    readText("banned_ids.txt")?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

actual fun saveBannedIds(ids: Set<String>) = writeText("banned_ids.txt", ids.joinToString("\n"))

actual fun loadSearchHistory(): String = readText("search_history.json") ?: ""

actual fun saveSearchHistory(json: String) = writeText("search_history.json", json)

actual fun imageModel(address: String): Any =
    if (address.startsWith("http")) address
    else File(address.removePrefix("file://"))

actual fun loadDeviceSettings(): Map<String, String> =
    readText("settings.txt")?.lines()?.mapNotNull { line ->
        line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }?.toMap() ?: emptyMap()

actual fun saveDeviceSettings(settings: Map<String, String>) =
    writeText("settings.txt", settings.entries.joinToString("\n") { "${it.key}=${it.value}" })

actual fun showMatchNotification(title: String, body: String) {}
actual fun schedulePolling(intervalMinutes: Int) {}
actual fun cancelPolling() {}

@androidx.compose.runtime.Composable
actual fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit = { onCity(null) }

@androidx.compose.runtime.Composable
actual fun rememberCoordDetector(onCoords: (Double?, Double?) -> Unit): () -> Unit = { onCoords(null, null) }

@Composable
actual fun ReadPositionIfAllowed(onCoords: (Double?, Double?) -> Unit) { }
