package io.github.tieo.arbay.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Listing(
    val id: String,
    val platformId: PlatformId,
    val externalId: String,
    val url: String,
    val title: String,
    val price: Money,
    val oldPrice: Money? = null,
    val negotiable: Boolean = false,
    val condition: Condition? = null,
    val imageUrls: List<String> = emptyList(),
    val location: Location? = null,
    val description: String? = null,
    val seller: Seller? = null,
    val shipping: Shipping? = null,
    val listingDate: Instant? = null,
    val soldDate: Instant? = null,
    val sold: Boolean = false,
    val gtin: String? = null,
    val mpn: String? = null,
    val scrapedAt: Instant,
    val extras: JsonObject? = null,
    val relevanceScore: Double? = null,
    val modelScores: Map<String, Double>? = null,
    val isExploration: Boolean = false,
    val vehicle: VehicleInfo? = null,
) {
    /** All-in price including shipping and platform fees */
    val effectivePrice: Money get() = Money(
        price.amount + (shipping?.cost?.amount ?: 0L),
        price.currency,
    )
}
