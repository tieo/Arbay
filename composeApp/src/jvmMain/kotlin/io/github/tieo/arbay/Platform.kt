package io.github.tieo.arbay

import java.awt.Desktop
import java.io.File
import java.net.URI

actual fun openBrowser(url: String) {
    try {
        Desktop.getDesktop().browse(URI(url))
    } catch (_: Exception) {}
}

private val bannedFile = File(System.getProperty("user.home"), ".arbay/banned_ids.txt")

actual fun loadBannedIds(): Set<String> {
    return try {
        if (!bannedFile.exists()) return emptySet()
        bannedFile.readLines().filter { it.isNotBlank() }.toSet()
    } catch (_: Exception) { emptySet() }
}

actual fun saveBannedIds(ids: Set<String>) {
    try {
        bannedFile.parentFile.mkdirs()
        bannedFile.writeText(ids.joinToString("\n"))
    } catch (_: Exception) {}
}

private val blockedTermsDir = File(System.getProperty("user.home"), ".arbay/blocked_terms")

private fun blockedTermsFile(query: String): File {
    val safe = query.lowercase().replace(Regex("[^a-z0-9]"), "_").take(80)
    return File(blockedTermsDir, "$safe.txt")
}

actual fun loadBlockedTerms(query: String): Set<String> {
    return try {
        val f = blockedTermsFile(query)
        if (!f.exists()) return emptySet()
        f.readLines().filter { it.isNotBlank() }.toSet()
    } catch (_: Exception) { emptySet() }
}

actual fun saveBlockedTerms(query: String, terms: Set<String>) {
    try {
        blockedTermsDir.mkdirs()
        val f = blockedTermsFile(query)
        if (terms.isEmpty()) f.delete()
        else f.writeText(terms.joinToString("\n"))
    } catch (_: Exception) {}
}
