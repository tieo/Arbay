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

private fun blockedTermsDir(): File? {
    val dir = File(appDir() ?: return null, "blocked_terms")
    dir.mkdirs()
    return dir
}

private fun blockedTermsFile(query: String): File? {
    val safe = query.lowercase().replace(Regex("[^a-z0-9]"), "_").take(80)
    return File(blockedTermsDir() ?: return null, "$safe.txt")
}

actual fun loadBlockedTerms(query: String): Set<String> {
    val f = blockedTermsFile(query) ?: return emptySet()
    return try {
        if (!f.exists()) emptySet()
        else f.readLines().filter { it.isNotBlank() }.toSet()
    } catch (_: Exception) { emptySet() }
}

actual fun saveBlockedTerms(query: String, terms: Set<String>) {
    val f = blockedTermsFile(query) ?: return
    try {
        if (terms.isEmpty()) f.delete()
        else f.writeText(terms.joinToString("\n"))
    } catch (_: Exception) {}
}
