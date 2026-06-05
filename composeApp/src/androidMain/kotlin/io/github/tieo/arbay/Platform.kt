package io.github.tieo.arbay

import android.content.Intent
import android.net.Uri
import java.io.File

actual fun openBrowser(url: String) {
    val activity = MainActivity.instance ?: return
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {}
}

private fun appDir(): File? = MainActivity.instance?.filesDir

actual fun loadBannedIds(): Set<String> {
    val f = File(appDir() ?: return emptySet(), "banned_ids.txt")
    return try {
        if (!f.exists()) emptySet()
        else f.readLines().filter { it.isNotBlank() }.toSet()
    } catch (_: Exception) { emptySet() }
}

actual fun saveBannedIds(ids: Set<String>) {
    val f = File(appDir() ?: return, "banned_ids.txt")
    try { f.writeText(ids.joinToString("\n")) } catch (_: Exception) {}
}


actual fun showMatchNotification(title: String, body: String) {
    NotificationHelper.showNewMatchNotification(title, body)
}

actual fun schedulePolling(intervalMinutes: Int) {
    val context = MainActivity.instance ?: return
    FreeItemPollWorker.schedule(context, intervalMinutes)
}

actual fun cancelPolling() {
    val context = MainActivity.instance ?: return
    FreeItemPollWorker.cancel(context)
}
