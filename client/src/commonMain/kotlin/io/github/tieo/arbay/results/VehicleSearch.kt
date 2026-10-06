package io.github.tieo.arbay.results

import io.github.tieo.arbay.CarTaxonomyStore
import io.github.tieo.arbay.history.SearchHistoryStore
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.CarMakeNode
import io.github.tieo.arbay.model.CarModelNode
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.toCarFilters
import io.github.tieo.arbay.model.withCarFilters

/**
 * A vehicle search as the form describes it: a make and a model (either may be left open), the
 * criteria, the markets to ask, and where to look.
 *
 * The search's words are the make and model, which is what the markets are asked for; with neither
 * the criteria alone are sent to the markets that filter by them.
 */
data class VehicleSearch(
    val make: CarMakeNode? = null,
    val model: CarModelNode? = null,
    val filters: CarFilters = CarFilters(),
    val platforms: List<PlatformId> = MarketSets.vehicles,
    val near: String? = null,
    val radiusKm: Int? = null,
) {
    val query: String get() = listOfNotNull(make?.name, model?.name).joinToString(" ")
    val name: String get() = query.ifBlank { "Vehicle search" }

    /** [base] with this search's words, criteria, markets and place written onto it. */
    fun applyTo(base: SearchQuery): SearchQuery = base.withCarFilters(filters).copy(
        text = query,
        platforms = platforms,
        category = MarketGroup.VEHICLES,
        location = near?.trim()?.takeIf { it.isNotEmpty() },
        radiusKm = radiusKm ?: 0,
    )

    /** Runs it the way every search runs: kept in history with all it says, so its results read
     *  their criteria, markets and place back from there. */
    fun record() {
        val base = SearchHistoryStore.entryFor(query, MarketGroup.VEHICLES)?.searchQuery
            ?: SearchQuery(text = query, category = MarketGroup.VEHICLES)
        SearchHistoryStore.record(name, applyTo(base))
    }

    companion object {
        /** The form as a saved or remembered search last left it. */
        fun of(saved: SearchQuery): VehicleSearch {
            val (make, model) = resolveCarNodes(saved.text)
            return VehicleSearch(
                make = make,
                model = model,
                filters = saved.toCarFilters() ?: CarFilters(),
                platforms = saved.platforms.ifEmpty { MarketSets.vehicles },
                near = saved.location,
                radiusKm = saved.radiusKm.takeIf { it > 0 },
            )
        }

        /** Every make the picker offers, as the taxonomy has them. */
        val makes: List<CarMakeNode> get() = CarTaxonomyStore.taxonomy.makes
    }
}
