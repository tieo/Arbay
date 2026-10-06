package io.github.tieo.arbay

import androidx.compose.runtime.Composable
import java.io.File

actual fun imageModel(address: String): Any =
    if (address.startsWith("http")) address
    else File(address.removePrefix("file://"))

actual fun schedulePolling(intervalMinutes: Int) {}
actual fun cancelPolling() {}

@androidx.compose.runtime.Composable
actual fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit = { onCity(null) }

@androidx.compose.runtime.Composable
actual fun rememberCoordDetector(onCoords: (Double?, Double?) -> Unit): () -> Unit = { onCoords(null, null) }

@Composable
actual fun ReadPositionIfAllowed(onCoords: (Double?, Double?) -> Unit) { }
