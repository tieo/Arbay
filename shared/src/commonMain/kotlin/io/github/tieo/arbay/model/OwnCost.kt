package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * What a vehicle costs to own over [years]: its purchase less what it is worth at the end, its
 * fixed costs, workshop and tyres, energy and care, at [kmPerYear], with the same total at other
 * yearly distances beside it. [assumptions] names every figure that is an estimate rather than the
 * ad's or a source's own, and [notCounted] what is left out.
 */
@Serializable
data class OwnCost(
    val years: Int,
    val kmPerYear: Int,
    val totalEur: Int,
    /** The total at each yearly distance, in km. */
    val totalsByKm: Map<Int, Int> = emptyMap(),
    val parts: List<OwnCostPart> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val notCounted: List<String> = emptyList(),
    /** Where the figures come from ("ADAC Herbst/Winter 2026, model row ID.3 Neo (58 kWh) Life"). */
    val source: String? = null,
)

@Serializable
data class OwnCostPart(val what: String, val eur: Int, val note: String? = null)

/** The yearly distances an own-cost total is shown at, side by side. */
val OWN_COST_KM: List<Int> = listOf(5_000, 10_000, 20_000)
