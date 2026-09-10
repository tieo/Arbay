package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery

/**
 * The term this platform is searched with: what was typed, unless the search carries a term of its
 * own for this market's language.
 *
 * A cross-border market answers its own language, so "Parkettschleifmaschine" reaches eBay Italy as
 * nothing at all while "levigatrice per parquet" reaches the same machines. Which is why the option
 * exists — but the term is one the searcher accepted and can edit, never one produced here on the
 * way out: a market asked something nobody saw cannot be held to its answer.
 */
fun localizedQuery(base: SearchQuery, platform: PlatformId): SearchQuery {
    if (!base.reach.otherLanguages || base.text.isBlank()) return base
    val language = platform.searchLanguage
    val term = base.reach.termByLanguage[language]?.takeIf { it.isNotBlank() } ?: return base
    if (term == base.text) return base
    return base.copy(text = term)
}
