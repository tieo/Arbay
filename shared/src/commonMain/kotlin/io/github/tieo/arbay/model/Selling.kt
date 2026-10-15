package io.github.tieo.arbay.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * An ad the user puts up for sale on Kleinanzeigen through their own account, kept as a draft in
 * Arbay until the user posts it.
 *
 * A draft usually starts from an ad the user bought from: its photos, cropped a little so they are
 * not the seller's files, its text, its category and the details the seller picked. The user then
 * changes what differs, and nothing reaches the market before they press post.
 */
@Serializable
data class AdDraft(
    val id: String,
    val market: PlatformId = PlatformId.KLEINANZEIGEN,
    /** The Arbay listing id of the ad this one was started from. */
    val fromListingId: String? = null,
    /** The market's category, as its post form takes it (Kleinanzeigen's "Handy & Telefon" is 173). */
    val categoryId: String,
    val title: String,
    val description: String,
    /**
     * The details the post form asks for, by the label it shows them under ("Marke", "Farbe",
     * "Zustand") with the option picked, as the form words it ("Google", "Gut").
     */
    val attributes: Map<String, String> = emptyMap(),
    /** Whole euros; the form takes no cents. Null with [AdPriceType.GIVE_AWAY]. */
    val priceEur: Int? = null,
    val priceType: AdPriceType = AdPriceType.NEGOTIABLE,
    /** Whether the item ships; off, it is for pickup only. */
    val shipping: Boolean = true,
    /** Kleinanzeigen's "Direkt kaufen": buyers pay through "Sicher bezahlen". */
    val buyNow: Boolean = true,
    /** The draft's photos in the order the ad shows them; each is served at [photoPath]. */
    val photos: List<String> = emptyList(),
    val createdAt: Instant? = null,
    /** The market's id of the ad once posted. */
    val postedAdId: String? = null,
    val postedAt: Instant? = null,
)

@Serializable
enum class AdPriceType(val label: String) {
    FIXED("Festpreis"), NEGOTIABLE("VB"), GIVE_AWAY("Zu verschenken");
}

/** Where a draft's photo is served. */
fun AdDraft.photoPath(photo: String): String = "/api/selling/drafts/$id/photos/$photo"

/** The posted ad on the market, once there is one. */
fun AdDraft.postedUrl(): String? = postedAdId?.let { "https://www.kleinanzeigen.de/s-anzeige/$it" }

/** What keeps the draft from being posted, worded for the user, or null. */
fun AdDraft.problem(): String? = when {
    title.isBlank() -> "The ad needs a title"
    description.isBlank() -> "The ad needs a text"
    photos.isEmpty() -> "The ad needs a photo"
    priceType != AdPriceType.GIVE_AWAY && (priceEur ?: 0) <= 0 -> "The ad needs a price"
    else -> null
}

/** The ad in a line: posted or not, and its price. */
fun AdDraft.saleLine(): String = listOfNotNull(
    if (postedAdId != null) "Posted" else "Draft",
    if (priceType == AdPriceType.GIVE_AWAY) priceType.label else priceEur?.let { "$it € ${if (priceType == AdPriceType.NEGOTIABLE) "VB" else ""}".trim() },
    if (shipping) null else "pickup only",
).joinToString(" · ")

/** What a draft starts from: an ad the user bought from, by its Arbay listing id. */
@Serializable
data class NewAdDraft(val listingId: String)

/** What posting answered: the draft as it now stands, and what went wrong when it did. */
@Serializable
data class PostAdResult(val draft: AdDraft, val error: String? = null)
