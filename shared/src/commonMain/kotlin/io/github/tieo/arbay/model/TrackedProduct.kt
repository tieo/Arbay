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
    val alertRules: List<AlertRule> = emptyList(),
    val createdAt: Instant,
    val active: Boolean = true,
)

@Serializable
data class AlertRule(
    val id: String,
    val type: AlertType,
    val threshold: Long? = null,
)

@Serializable
enum class AlertType {
    PRICE_BELOW,
    PRICE_DROP_PERCENT,
    NEW_LISTING,
    ARBITRAGE,
}
