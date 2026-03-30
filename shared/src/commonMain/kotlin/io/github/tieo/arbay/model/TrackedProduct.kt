package io.github.tieo.arbay.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class ProductIdentifier(
    val gtins: List<String> = emptyList(),
    val mpn: String? = null,
)

@Serializable
data class TrackedProduct(
    val id: String,
    val name: String,
    val searchQuery: SearchQuery,
    val identifiers: ProductIdentifier = ProductIdentifier(),
    val createdAt: Instant,
)
