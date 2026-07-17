package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/**
 * A country to source from in a cross-border product search. The search term is translated into the
 * market's [language] before its marketplaces are queried, so "Parkettschleifmaschine" also finds
 * the Italian "levigatrice per parquet". German-speaking markets share the language, so no
 * translation happens for them.
 */
@Serializable
enum class TargetMarket(
    val code: String,        // ISO 3166-1 alpha-2
    val displayName: String,
    val language: String,    // ISO 639-1, for translating the query
) {
    DE("DE", "Germany", "de"),
    AT("AT", "Austria", "de"),
    CH("CH", "Switzerland", "de"),
    IT("IT", "Italy", "it"),
    FR("FR", "France", "fr"),
    PL("PL", "Poland", "pl"),
    CZ("CZ", "Czechia", "cs");

    companion object {
        /** Default: search the home market only, so a plain search is unchanged until the user opts
         *  into cross-border. */
        val default = listOf(DE)
    }
}
