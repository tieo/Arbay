package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class Seller(
    val name: String,
    val type: SellerType? = null,
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val url: String? = null,
)

@Serializable
enum class SellerType {
    PRIVATE, BUSINESS
}
