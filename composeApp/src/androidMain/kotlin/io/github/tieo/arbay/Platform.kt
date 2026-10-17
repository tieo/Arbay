package io.github.tieo.arbay

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

actual fun imageModel(address: String): Any =
    if (address.startsWith("http")) address else File(address.removePrefix("file://"))

actual fun schedulePolling(intervalMinutes: Int) {
    FreeItemPollWorker.schedule(ArbayApplication.context, intervalMinutes)
}

actual fun cancelPolling() {
    FreeItemPollWorker.cancel(ArbayApplication.context)
}

/** Kleinanzeigen takes up to 20 photos an ad. */
private const val MAX_AD_PHOTOS = 20

@Composable
actual fun rememberPhotoPicker(onPicked: (List<ByteArray>) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_AD_PHOTOS)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val photos = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            }
            onPicked(photos)
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}
