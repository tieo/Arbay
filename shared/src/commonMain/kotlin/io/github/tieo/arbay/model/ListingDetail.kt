package io.github.tieo.arbay.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * What a listing's own page says, beyond what its card on the results page carried.
 *
 * The card is a summary the market writes for a list: a price, a photo, a line of specs. The page
 * is the ad itself. Three things live only there and each of them was missing from a real screen —
 * the specs a filter needs (power, gearbox), the seller's own description (where a van's wheelbase
 * is written down, when it is written down at all), and where the thing stands (eBay says it on the
 * page and nowhere on the card).
 *
 * Fetched at most once per listing and cached, since none of it changes while the ad is up.
 */
@Serializable
data class ListingDetail(
    val vehicle: VehicleInfo? = null,
    /** The seller's own text, as the page shows it. */
    val description: String? = null,
    val location: Location? = null,
    /** Shipping as the page states it, where the card only said it ships: Kleinanzeigen's page
     *  names the cheapest carrier the seller allows ("+ Versand ab 6,19 €"). */
    val shipping: Shipping? = null,
    /** When the ad first went up, where only its page says it: mobile.de's results carry no date. */
    val listedAt: Instant? = null,
    /** How many reviews the dealer's stars rest on, where only the page has the whole number:
     *  a mobile.de result card counts some of them (12 where the page says 474). */
    val sellerReviews: Int? = null,
    /** Who sells it, as the page shows them where the card names nobody: a Kleinanzeigen card
     *  carries an account number only. */
    val seller: SellerProfile? = null,
    /** Every photo of the ad's gallery, where the card carried only the first. */
    val photos: List<String> = emptyList(),
    /** How many the listing still has, where its page says ("3 verfügbar", "Letzter Artikel");
     *  [moreAvailable] where it says only that there are more than that ("Mehr als 10 verfügbar"). */
    val available: Int? = null,
    val moreAvailable: Boolean = false,
    /** Whether the seller's text says it is sold broken ("läuft nicht an", "als defekt verkauft"),
     *  which a card without the text cannot show. */
    val soldBroken: Boolean = false,
)

/**
 * A seller as their ad's page presents them. An account opened the day it posts a cheap ad, with
 * no rating yet, is how a scam looks on Kleinanzeigen; one years old with "TOP Zufriedenheit" is
 * how a trustworthy seller does.
 */
@Serializable
data class SellerProfile(
    val name: String? = null,
    val type: SellerType? = null,
    /** The day the account was opened ("Aktiv seit 24.10.2026"). */
    val since: LocalDate? = null,
    /** The badges the market gave the seller for their ratings, as it words them
     *  ("TOP Zufriedenheit", "Sehr freundlich", "Zuverlässig"); none on an account never rated. */
    val badges: List<String> = emptyList(),
)

/** The seller in a few words, with how long the account has existed and what its ratings earned:
 *  "sold by Markus S., private, member since 2014-03-02, TOP Zufriedenheit, Freundlich". An
 *  account without badges says so, since that is the warning a new account carries. */
val SellerProfile.fact: String get() = listOfNotNull(
    "sold by ${name ?: "a seller"}",
    when (type) { SellerType.PRIVATE -> "private"; SellerType.BUSINESS -> "business"; null -> null },
    since?.let { "member since $it" },
    badges.joinToString(", ").ifEmpty { if (since != null) "no rating badges" else null },
).joinToString(", ")

/**
 * Whether an ad can still be bought, as its own page says right now. Kleinanzeigen keeps a page up
 * after the seller deletes or pauses the ad and only drops it from search, so an ad that opens is
 * not necessarily for sale.
 */
@Serializable
enum class AdState { LIVE, PAUSED, DELETED }

/**
 * An ad as its own page has it right now, read for an offer about to be written: a seller changes
 * the price, pauses the ad or turns shipping off between a search and the message, and an offer
 * worked out from what the search saw went out above a lowered ask or to a pickup-only ad.
 */
@Serializable
data class AdNow(
    val state: AdState? = null,
    /** The asking price the page shows. */
    val price: Money? = null,
    val shipping: Shipping? = null,
)

