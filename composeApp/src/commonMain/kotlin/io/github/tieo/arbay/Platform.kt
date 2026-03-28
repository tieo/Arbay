package io.github.tieo.arbay

expect fun openBrowser(url: String)

expect fun loadBannedIds(): Set<String>
expect fun saveBannedIds(ids: Set<String>)

expect fun loadBlockedTerms(query: String): Set<String>
expect fun saveBlockedTerms(query: String, terms: Set<String>)
