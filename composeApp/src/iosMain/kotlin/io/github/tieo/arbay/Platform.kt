package io.github.tieo.arbay

import androidx.compose.runtime.Composable

// STUBS, NOT AN IMPLEMENTATION. Nothing on iOS is scheduled or located: every poll is dropped and
// every position is unknown. This target is not shipped; Android (androidMain/Platform.kt) is the
// implementation to follow when it is built for real.

actual fun imageModel(address: String): Any = address

actual fun schedulePolling(intervalMinutes: Int) {}
actual fun cancelPolling() {}

@androidx.compose.runtime.Composable
actual fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit = { onCity(null) }

@androidx.compose.runtime.Composable
actual fun rememberCoordDetector(onCoords: (Double?, Double?) -> Unit): () -> Unit = { onCoords(null, null) }

@Composable
actual fun ReadPositionIfAllowed(onCoords: (Double?, Double?) -> Unit) { }
