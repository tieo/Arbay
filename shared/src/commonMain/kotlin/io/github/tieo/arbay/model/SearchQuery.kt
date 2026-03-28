package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class SearchQuery(
    val text: String,
    val platforms: List<PlatformId> = PlatformId.entries,
    val minPrice: Money? = null,
    val maxPrice: Money? = null,
    val condition: List<Condition>? = null,
    val soldOnly: Boolean = false,
    val excludeKeywords: List<String> = emptyList(),
) {
    /** Search text with negative keywords and OR logic resolved — for platforms that don't support exclusion/OR syntax.
     *  For OR queries, picks the group with the most tokens (most specific variant). */
    val positiveText: String
        get() {
            val withoutNegatives = text.split(" ")
                .filter { it.isNotBlank() && !it.startsWith("-") }
                .joinToString(" ")
            return if (withoutNegatives.contains(" OR ", ignoreCase = true)) {
                // Split on OR and pick the group with the most tokens (most descriptive)
                withoutNegatives.split(Regex("\\s+OR\\s+", RegexOption.IGNORE_CASE))
                    .maxByOrNull { it.trim().split("\\s+".toRegex()).size }
                    ?.trim() ?: withoutNegatives
            } else {
                withoutNegatives
            }
        }
}
