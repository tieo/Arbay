package io.github.tieo.arbay

// STUBS, NOT AN IMPLEMENTATION. Nothing on the web build is stored or scheduled: every load below returns
// nothing and every save, notification and poll is silently dropped. This target is not shipped.
// A settings change, a banned listing or a search history "working" here means nothing; Android
// (androidMain/Platform.kt) is the implementation to follow when this target is built for real.

actual fun openBrowser(url: String) {}
actual fun loadBannedIds(): Set<String> = emptySet()
actual fun saveBannedIds(ids: Set<String>) {}
actual fun loadSearchHistory(): String = ""
actual fun saveSearchHistory(json: String) {}

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

@Composable
actual fun ReadPositionIfAllowed(onCoords: (Double?, Double?) -> Unit) { }
