package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class Seller(
    /** What the market calls the seller on its card, where it names one at all: a dealer's
     *  company name. Kleinanzeigen's cards name nobody, only an account number. */
    val name: String? = null,
    val type: SellerType? = null,
    val rating: Double? = null,
    val reviewCount: Int? = null,
    val url: String? = null,
    /** The market's own number for the seller's account, which stays when a dealer renames itself
     *  or writes its name differently on two ads. What a blocked dealer is matched by. */
    val id: String? = null,
)

@Serializable
enum class SellerType {
    PRIVATE, BUSINESS
}

/** The seller as the app writes them: by name, or by the market's account number where the card
 *  gives no name. */
val Seller.shownName: String get() = name?.takeIf { it.isNotBlank() } ?: id?.let { "seller $it" } ?: "this seller"
