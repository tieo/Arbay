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
    // How this is sold, when the market says. An auction's price is the bid so far, which is not
    // what the thing costs: a Crucial module alerted at €10.50 was €21.50 the next day and still
    // rising. Null where the market does not distinguish, or the listing predates this being read.
    val saleType: SaleType? = null,
    // When the bidding ends, for an auction that says. What makes a bid worth being told about is
    // that it is nearly over — before that, the number means nothing yet.
    val auctionEndsAt: Instant? = null,
    val bidCount: Int? = null,
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
    /** All-in price including shipping and platform fees. A delivery charge in another currency
     *  than the price is left out rather than added as if it were the same one. */
    val effectivePrice: Money get() = Money(
        price.amount + (shipping?.cost?.takeIf { it.currency == price.currency }?.amount ?: 0L),
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

/** How a listing is sold, where the market says so. */
@Serializable
enum class SaleType(val label: String) {
    /** A price the seller is asking, which is what it costs to buy it now. */
    FIXED_PRICE("Buy now"),

    /** A price that is only the highest bid so far, and rises until the auction ends. */
    AUCTION("Auction"),
}
