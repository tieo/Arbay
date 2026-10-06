package io.github.tieo.arbay.results

import io.github.tieo.arbay.CarTaxonomyStore
import io.github.tieo.arbay.history.SearchHistoryEntry
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.CarMakeNode
import io.github.tieo.arbay.model.CarModelNode
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.model.toCarFilters

/**
 * Best-effort make/model nodes for prefilling the vehicle-search form's dropdowns, from the text of
 * a search already known to be a car search — see [ResultsView.isCar]. Never used to decide whether
 * something IS a car search: that used to be guessed from whether a make's name appeared anywhere
 * in the query text, which misfires on any make that is also an ordinary word (RAM is a real
 * vehicle brand and also what a "32GB SODIMM RAM" listing calls itself). A wrong guess here only
 * means the wrong dropdown is preselected in a form the user is looking straight at; it can no
 * longer silently reroute an unrelated search into vehicle mode.
 */
fun resolveCarNodes(query: String): Pair<CarMakeNode?, CarModelNode?> {
    val q = query.trim().lowercase()
    fun leads(word: String) = q == word || q.startsWith("$word ")
    val make = CarTaxonomyStore.taxonomy.makes.firstOrNull { m ->
        val n = m.name.lowercase()
        leads(n) || (n == "volkswagen" && leads("vw"))
    }
    val model = make?.models?.firstOrNull { q.contains(it.name.lowercase()) }
    return make to model
}

/**
 * A search whose results are on screen. Every way in — opening a bookmark, previewing a product,
 * running the car form — produces one of these, so the results sheet is wired once instead of
 * three times with three different notions of what saving, editing and blocking mean.
 *
 * [isCar] marks it a vehicle search and gives the sheet its car view; it is carried explicitly
 * from wherever the search actually came from, never re-derived from the query text. The bookmark
 * behind it, if any, is looked up live from the saved searches by query text rather than carried
 * here, so saving and removing take effect without rebuilding this.
 */
data class ResultsView(
    val name: String,
    val query: String,
    val platforms: List<PlatformId>?,
    val category: MarketGroup,
    val make: CarMakeNode? = null,
    val model: CarModelNode? = null,
    val filters: CarFilters? = null,
    // Alternate phrasings and excluded words this search carries — a catalogue product's own
    // data, or whatever a bookmark/history entry was last narrowed to. Never embedded in [query]
    // itself.
    val aliases: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    /** Back leads to the car form it was run from, else to discovery, else nowhere. */
    val fromCarForm: Boolean = false,
    val fromDiscovery: Boolean = false,
    // What this saved search had found since it was last opened, captured at the moment of opening
    // because opening is what clears it. Empty for every other way in, which has no such backlog.
    val newListingIds: Set<String> = emptySet(),
    // Those findings themselves, as the watch stored them, when the results were opened from the
    // "n new" badge. Non-null means this view does not crawl to fill itself. Carried here so that
    // every way of closing the results drops them with the view.
    val stored: List<Listing>? = null,
    // Where this search is centred and how far it reaches, when it says.
    val near: String? = null,
    val radiusKm: Int? = null,
) {
    /** Whether this is the vehicle-search view — computed from [category] rather than stored
     *  alongside it, so the two can never disagree. */
    val isCar: Boolean get() = category == MarketGroup.VEHICLES

    companion object {
        /** The results of a saved search. A vehicle bookmark gets the car view even with no
         *  filters set yet, so the filters can be added from there. */
        fun of(product: TrackedProduct, newListingIds: Set<String> = emptySet()): ResultsView {
            val category = product.searchQuery.category
            val isCar = category == MarketGroup.VEHICLES
            val (make, model) = if (isCar) resolveCarNodes(product.searchQuery.text) else null to null
            return ResultsView(
                name = product.name,
                query = product.searchQuery.text,
                platforms = product.searchQuery.platforms,
                category = category,
                make = make,
                model = model,
                filters = if (isCar) product.searchQuery.toCarFilters() ?: CarFilters() else null,
                aliases = product.searchQuery.aliases,
                excludeKeywords = product.searchQuery.excludeKeywords,
                newListingIds = newListingIds,
            )
        }

        /** The results of a search that was run before but never saved — same shape as reopening a
         *  bookmark, since a history entry carries the same [SearchQuery]. */
        fun of(entry: SearchHistoryEntry): ResultsView {
            val category = entry.searchQuery.category
            val isCar = category == MarketGroup.VEHICLES
            val (make, model) = if (isCar) resolveCarNodes(entry.searchQuery.text) else null to null
            return ResultsView(
                name = entry.name,
                query = entry.searchQuery.text,
                platforms = entry.searchQuery.platforms,
                category = category,
                make = make,
                model = model,
                filters = if (isCar) entry.searchQuery.toCarFilters() ?: CarFilters() else null,
                aliases = entry.searchQuery.aliases,
                excludeKeywords = entry.searchQuery.excludeKeywords,
            )
        }

        /** The results of a query typed into the plain search box or a catalogue product. Never
         *  the vehicle-search view (that only opens through the car form) — [category] still
         *  distinguishes a catalogue car (e.g. "VW Golf 8") from an ordinary product search, since
         *  that decides which markets it defaults to reaching. Aliases/excludeKeywords are a
         *  catalogue product's own data (empty for a plain typed search). */
        fun of(
            name: String,
            query: String,
            platforms: List<PlatformId>?,
            category: MarketGroup,
            fromDiscovery: Boolean = false,
            aliases: List<String> = emptyList(),
            excludeKeywords: List<String> = emptyList(),
        ): ResultsView = ResultsView(
            name = name,
            query = query,
            platforms = platforms,
            category = category,
            fromDiscovery = fromDiscovery,
            aliases = aliases,
            excludeKeywords = excludeKeywords,
        )
    }
}
