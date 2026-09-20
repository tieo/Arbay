package io.github.tieo.arbay.ui.screen

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.withCarFilters

/**
 * The search a bookmark is saved with: the one on screen, narrowing and all.
 *
 * Until a search is bookmarked its filters live on its history entry, and bookmarking built a new
 * query out of the search text and its markets alone. Every narrowing the reader had set — the
 * price band, the conditions, how the thing is sold, the order, the markets they had unticked —
 * was dropped at the moment they saved it, and because the screen then reads its filters off the
 * bookmark instead of the history entry, they watched a screen of 188 offers with four filters
 * become 252 with two while tapping nothing but save.
 *
 * [asked] is the market list the search actually ran with. A search carrying none of its own is
 * saved with the markets it was searched with rather than with every market of its kind, which the
 * watch would otherwise re-run forever.
 */
fun bookmarkQuery(
    onScreen: SearchQuery?,
    text: String,
    asked: List<PlatformId>,
    category: MarketGroup,
    carFilters: CarFilters?,
    blockedWords: List<String>,
    aliases: List<String>,
): SearchQuery = (onScreen ?: SearchQuery(text = text, category = category))
    .copy(
        text = text,
        platforms = asked,
        category = category,
        excludeKeywords = blockedWords,
        aliases = aliases,
    )
    // Vehicle criteria are written only when the search has some. Writing none is how the car form
    // says "no criteria at all", which takes the price band with it, and a general search has no
    // criteria and a band of its own.
    .let { if (carFilters != null) it.withCarFilters(carFilters) else it }
