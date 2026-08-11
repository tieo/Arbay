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
    // Semantic match [0,1] to a car search's free-form idealDescription; null when none set. Used to
    // rank car results by how well each fits the described ideal.
    val matchScore: Double? = null,
    // Great-circle distance in km from the searcher's position to this listing, set when the search
    // carried the user's coordinates and the listing's location could be geocoded.
    val distanceKm: Double? = null,
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

/**
 * A title as it should be read, not as the market's HTML happened to serialise.
 *
 * eBay puts an inline image in some titles, which arrives as U+FFFC and draws on the phone as a
 * box reading OBJ. Control characters and runs of whitespace come from the same source.
 */
fun String.tidyTitle(): String =
    filter { it == '\n' || it.code >= 32 }
        .replace('\uFFFC', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
