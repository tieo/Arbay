package io.github.tieo.arbay

// STUBS, NOT AN IMPLEMENTATION. Nothing on iOS is stored: every load below returns nothing and
// every save and notification is silently dropped. This target is not shipped. A settings change,
// a banned listing or a search history "working" here means nothing; Android
// (androidMain/Device.android.kt) is the implementation to follow when this target is built for real.

actual fun openBrowser(url: String) {}
actual fun loadBannedIds(): Set<String> = emptySet()
actual fun saveBannedIds(ids: Set<String>) {}
actual fun loadSearchHistory(): String = ""
actual fun saveSearchHistory(json: String) {}

actual fun loadDeviceSettings(): Map<String, String> = emptyMap()
actual fun saveDeviceSettings(settings: Map<String, String>) {}

actual fun showMatchNotification(title: String, body: String) {}
