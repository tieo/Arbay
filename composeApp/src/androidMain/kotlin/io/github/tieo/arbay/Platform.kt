package io.github.tieo.arbay

import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import java.io.File

actual fun openBrowser(url: String) {
    try {
        ArbayApplication.context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {}
}

private fun appFile(name: String): File = File(ArbayApplication.context.filesDir, name)

// Nothing stored reads as nothing stored where there is no app process to store it in.
private fun hasStorage() = ArbayApplication.contextOrNull != null

// One writer at a time for the device's files, and each write lands whole: a process killed
// mid-write left an empty file before, which lost every banned listing, the whole search history
// or the chosen server in one go rather than just the change being made.
private val fileLock = Any()

private fun readText(name: String): String? = synchronized(fileLock) {
    if (!hasStorage()) return null
    val file = AtomicFile(appFile(name))
    try { file.readFully().toString(Charsets.UTF_8) } catch (_: java.io.IOException) { null }
}

private fun writeText(name: String, text: String) = synchronized(fileLock) {
    val file = AtomicFile(appFile(name))
    val out = file.startWrite()
    try {
        out.write(text.toByteArray(Charsets.UTF_8))
        file.finishWrite(out)
    } catch (e: Exception) {
        file.failWrite(out)
        throw e
    }
}

actual fun loadBannedIds(): Set<String> =
    readText("banned_ids.txt")?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

actual fun saveBannedIds(ids: Set<String>) = writeText("banned_ids.txt", ids.joinToString("\n"))

actual fun loadSearchHistory(): String = readText("search_history.json") ?: ""

actual fun saveSearchHistory(json: String) = writeText("search_history.json", json)

actual fun imageModel(address: String): Any =
    if (address.startsWith("http")) address else File(address.removePrefix("file://"))

actual fun loadDeviceSettings(): Map<String, String> =
    readText("settings.txt")?.lines()?.mapNotNull { line ->
        line.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }?.toMap() ?: emptyMap()

actual fun saveDeviceSettings(settings: Map<String, String>) =
    writeText("settings.txt", settings.entries.joinToString("\n") { "${it.key}=${it.value}" })

actual fun showMatchNotification(title: String, body: String) {
    NotificationHelper.showNewMatchNotification(title, body)
}

actual fun schedulePolling(intervalMinutes: Int) {
    FreeItemPollWorker.schedule(ArbayApplication.context, intervalMinutes)
}

actual fun cancelPolling() {
    FreeItemPollWorker.cancel(ArbayApplication.context)
}
