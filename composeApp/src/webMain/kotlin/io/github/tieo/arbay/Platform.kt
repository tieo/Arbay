package io.github.tieo.arbay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.tieo.arbay.api.ArbayClient
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.launch
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

/** A market's photo goes through the server: a page may only read an image from another host when
 *  that host allows it, and the markets' image hosts do not. The server's own images are its own. */
actual fun imageModel(address: String): Any {
    val origin = window.location.origin
    return if (address.startsWith("http") && !address.startsWith(origin)) {
        "$origin/api/image?url=${encodeURIComponent(address)}"
    } else address
}

private external fun encodeURIComponent(value: String): String

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

// A page runs only while it is open, so there is nothing to schedule: the server watches saved
// searches on its own, and the phone is what is woken for them.
actual fun schedulePolling(intervalMinutes: Int) {}
actual fun cancelPolling() {}

@Composable
actual fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val client = remember { ArbayClient() }
    return {
        readPosition { lat, lon ->
            if (lat == null || lon == null) onCity(null)
            else scope.launch { onCity(client.placeAt(lat, lon)) }
        }
    }
}

@Composable
actual fun rememberCoordDetector(onCoords: (Double?, Double?) -> Unit): () -> Unit =
    { readPosition(onCoords) }

/** Reads the position only where the site already may, never asking: the browser says whether
 *  geolocation is granted, and only then is it read. */
@Composable
actual fun ReadPositionIfAllowed(onCoords: (Double?, Double?) -> Unit) {
    LaunchedEffect(Unit) {
        whenGeolocationGranted { readPosition { lat, lon -> if (lat != null && lon != null) onCoords(lat, lon) } }
    }
}
