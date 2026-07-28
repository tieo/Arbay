package io.github.tieo.arbay

actual fun openBrowser(url: String) {}
actual fun loadBannedIds(): Set<String> = emptySet()
actual fun saveBannedIds(ids: Set<String>) {}

actual fun imageModel(address: String): Any = address

actual fun loadDeviceSettings(): Map<String, String> = emptyMap()
actual fun saveDeviceSettings(settings: Map<String, String>) {}

actual fun showMatchNotification(title: String, body: String) {}
actual fun schedulePolling(intervalMinutes: Int) {}
actual fun cancelPolling() {}

@androidx.compose.runtime.Composable
actual fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit = { onCity(null) }

@androidx.compose.runtime.Composable
actual fun rememberCoordDetector(onCoords: (Double?, Double?) -> Unit): () -> Unit = { onCoords(null, null) }
