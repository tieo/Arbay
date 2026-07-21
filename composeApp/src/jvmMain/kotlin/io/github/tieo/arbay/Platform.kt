package io.github.tieo.arbay

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

actual fun showMatchNotification(title: String, body: String) {}
actual fun schedulePolling(intervalMinutes: Int) {}
actual fun cancelPolling() {}

@androidx.compose.runtime.Composable
actual fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit = { onCity(null) }

@androidx.compose.runtime.Composable
actual fun rememberCoordDetector(onCoords: (Double?, Double?) -> Unit): () -> Unit = { onCoords(null, null) }
