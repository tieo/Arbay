package io.github.tieo.arbay.history

import io.github.tieo.arbay.loadSearchHistory
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SearchQueryMigration
import io.github.tieo.arbay.model.SortMode
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.toCarFilters
import io.github.tieo.arbay.saveSearchHistory
import io.github.tieo.arbay.ui.screen.format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.encodeToString

/**
 * A search that was run, with whatever it was last narrowed to.
 *
 * The exact shape of a bookmark's own [searchQuery] — price band, condition, sort, which markets,
 * blocked words, vehicle criteria — because the two answer the same question ("what was this search
 * asking for") and a saved search already answers it this way. The difference is only that nobody
 * chose to keep this one.
 */
@Serializable
data class SearchHistoryEntry(
    val name: String,
    val searchQuery: SearchQuery,
    val lastRunAt: Instant,
)

/** The one line under a history row: what this search was narrowed to, in the order a reader
 *  weighs it — vehicle criteria first since they cut the hardest, then price, then everything
 *  else. Empty means it was run and never touched. */
fun SearchHistoryEntry.summary(): String {
    val q = searchQuery
    val parts = buildList {
        q.toCarFilters()?.let { f ->
            when {
                f.firstRegFromYear != null && f.firstRegToYear != null -> add("${f.firstRegFromYear}–${f.firstRegToYear}")
                f.firstRegFromYear != null -> add("from ${f.firstRegFromYear}")
                f.firstRegToYear != null -> add("until ${f.firstRegToYear}")
            }
            f.maxMileageKm?.let { add("≤${grouped(it)} km") }
            f.minPowerKw?.let { add("≥$it kW") }
            f.transmission?.let { add(if (it == Transmission.AUTOMATIC) "Automatik" else "Schaltgetriebe") }
        }
        val price = listOfNotNull(q.minPrice?.format(), q.maxPrice?.format())
        if (price.isNotEmpty()) add(price.joinToString("–"))
        q.condition?.let { conds ->
            if (conds.isNotEmpty()) add(if (conds.singleOrNull() == Condition.NEW) "New" else "Used")
        }
        q.sort?.takeIf { it != SortMode.BEST_MATCH }?.let { add(it.label) }
        val narrowedTo = q.showOnlyMarkets.size + q.showOnlyCountries.size
        if (narrowedTo > 0) add(if (narrowedTo == 1) "1 market" else "$narrowedTo markets")
        if (q.excludeKeywords.isNotEmpty()) {
            add(if (q.excludeKeywords.size == 1) "1 word blocked" else "${q.excludeKeywords.size} words blocked")
        }
    }
    return if (parts.isEmpty()) "No filters applied" else parts.joinToString(" · ")
}

private fun grouped(value: Int): String {
    val digits = value.toString()
    return digits.reversed().chunked(3).joinToString(",").reversed()
}

/**
 * Every search that has been run, most recent first, with whatever it was last narrowed to.
 *
 * Device-local only — never sent to the server, never shared between devices. A search's filters
 * live somewhere the moment it is opened: on the bookmark if it is saved, in here otherwise, so
 * narrowing an unsaved search is not lost the instant its results sheet closes.
 */
object SearchHistoryStore {
    private const val MAX_ENTRIES = 30
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<SearchHistoryEntry>> = _entries

    private fun key(text: String) = text.trim().lowercase()

    fun entryFor(query: String): SearchHistoryEntry? =
        _entries.value.firstOrNull { key(it.searchQuery.text) == key(query) }

    /** The full query to persist, whichever a caller already has: what history already knows about
     *  this search, or a fresh one built from what was just opened with. [category] decides the
     *  platforms a fresh entry defaults to — never "every platform", which is what searching a
     *  parkettschleifmaschine against car and real-estate sites forever turned out to mean. */
    fun baseQuery(query: String, platforms: List<PlatformId>?, carFilters: CarFilters?, category: MarketGroup): SearchQuery =
        entryFor(query)?.searchQuery
            ?: SearchQuery(text = query, platforms = platforms ?: MarketSets.platformsFor(category), carFilters = carFilters, category = category)

    /** A search was opened. Keeps whatever it was narrowed to last time; only the display name and
     *  freshly-known platforms/vehicle criteria are refreshed, so reopening the same search does not
     *  reset a price band or blocked word set the way starting a new one should not inherit them.
     *  [category] is what this search IS, not something to keep re-guessing — an existing entry's
     *  own category wins over whatever this particular open call happens to pass. */
    fun recordOpen(
        name: String,
        query: String,
        platforms: List<PlatformId>?,
        carFilters: CarFilters?,
        category: MarketGroup,
        aliases: List<String>? = null,
        excludeKeywords: List<String>? = null,
    ) {
        if (query.isBlank()) return
        val existing = entryFor(query)
        val resolvedCategory = existing?.searchQuery?.category ?: category
        val q = (existing?.searchQuery ?: SearchQuery(text = query, category = resolvedCategory)).copy(
            platforms = platforms ?: existing?.searchQuery?.platforms ?: MarketSets.platformsFor(resolvedCategory),
            carFilters = carFilters ?: existing?.searchQuery?.carFilters,
            category = resolvedCategory,
            aliases = aliases ?: existing?.searchQuery?.aliases ?: emptyList(),
            excludeKeywords = excludeKeywords ?: existing?.searchQuery?.excludeKeywords ?: emptyList(),
        )
        record(name, q)
    }

    /** The full narrowed state of a search, written wholesale — what a bookmark's own filter
     *  persistence already does, applied here for a search nobody chose to keep. */
    fun record(name: String, query: SearchQuery) {
        if (query.text.isBlank()) return
        val k = key(query.text)
        val without = _entries.value.filterNot { key(it.searchQuery.text) == k }
        _entries.value = (listOf(SearchHistoryEntry(name, query, Clock.System.now())) + without).take(MAX_ENTRIES)
        persist()
    }

    fun remove(query: String) {
        _entries.value = _entries.value.filterNot { key(it.searchQuery.text) == key(query) }
        persist()
    }

    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    private fun persist() {
        try { saveSearchHistory(json.encodeToString(_entries.value)) } catch (_: Exception) {}
    }

    private fun load(): List<SearchHistoryEntry> = try {
        val raw = loadSearchHistory()
        if (raw.isBlank()) return emptyList()
        val parsed = json.parseToJsonElement(raw) as JsonArray
        val migrated = SearchQueryMigration.migrateList(parsed)
        val entries = json.decodeFromJsonElement<List<SearchHistoryEntry>>(migrated)
        // Old entries missing searchQuery.category (or carrying platforms outside it) were just
        // backfilled in memory — persist that once so the file self-heals instead of re-migrating
        // from the same stale JSON on every load.
        if (migrated != parsed) {
            try { saveSearchHistory(json.encodeToString(entries)) } catch (_: Exception) {}
        }
        entries
    } catch (_: Exception) { emptyList() }
}
