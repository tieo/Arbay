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
)
