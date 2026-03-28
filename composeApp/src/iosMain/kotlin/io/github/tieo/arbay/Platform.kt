package io.github.tieo.arbay

actual fun openBrowser(url: String) {}
actual fun loadBannedIds(): Set<String> = emptySet()
actual fun saveBannedIds(ids: Set<String>) {}

actual fun loadBlockedTerms(query: String): Set<String> = emptySet()
actual fun saveBlockedTerms(query: String, terms: Set<String>) {}
