package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/** What kind of thing a search is for — the one tag a search carries, set once at creation and
 *  never re-derived from its text. Decides both which markets it reaches by default and how the
 *  markets picker groups its chips. */
@Serializable
enum class MarketGroup(val label: String) {
    GENERAL("General marketplaces"),
    VEHICLES("Vehicle sites"),
    REAL_ESTATE("Real estate"),
}

/**
 * Which markets a kind of search covers.
 *
 * One list per kind, in one place: a vehicle search started from a category tile and the same
 * search typed into the form used to cover 5 markets and 25 respectively, both called "every
 * market", with nothing on screen saying which was narrower.
 */
object MarketSets {

    /** Everywhere a vehicle can be found, home markets first and then the neighbouring countries
     *  a car is worth importing from. */
    val vehicles: List<PlatformId> = listOf(
        PlatformId.AUTOSCOUT24, PlatformId.MOBILE_DE, PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE,
        PlatformId.TRUCKSCOUT24, PlatformId.OTOMOTO, PlatformId.SAUTO, PlatformId.DBA,
        PlatformId.BILBASEN, PlatformId.BYTBIL, PlatformId.MARKTPLAATS, PlatformId.WILLHABEN,
        PlatformId.AUTOSCOUT24_IT, PlatformId.AUTOSCOUT24_FR, PlatformId.AUTOSCOUT24_ES,
        PlatformId.AUTOSCOUT24_BE, PlatformId.AUTOVIT, PlatformId.RICARDO, PlatformId.SUBITO,
        PlatformId.TWEEDEHANDS, PlatformId.AUTOPLIUS, PlatformId.NETTIAUTO, PlatformId.FINN,
        PlatformId.OLX_PT, PlatformId.KUPUJEM,
    )

    /** Everywhere else a thing is sold: the general marketplaces, the price comparers, the
     *  refurbishers, and the cross-border markets that are crawled in their own language. */
    val general: List<PlatformId> = listOf(
        PlatformId.EBAY_DE, PlatformId.EBAY_COM, PlatformId.KLEINANZEIGEN, PlatformId.AMAZON_DE,
        PlatformId.IDEALO, PlatformId.GEIZHALS, PlatformId.BACKMARKET_DE, PlatformId.REBUY,
        PlatformId.REFURBED, PlatformId.VINTED_DE, PlatformId.WILLHABEN, PlatformId.MARKTPLAATS,
        PlatformId.EBAY_IT, PlatformId.EBAY_FR, PlatformId.EBAY_ES, PlatformId.TWEEDEHANDS,
        PlatformId.RICARDO, PlatformId.SUBITO,
    )

    /** Where a home is found. Its own group rather than folded into general: a housing-shaped
     *  search has somewhere to grow into instead of falling through both lists uncounted, which
     *  is exactly what left ImmoScout24 reachable only by explicitly forcing every platform in. */
    val realEstate: List<PlatformId> = listOf(PlatformId.IMMOSCOUT24)

    fun platformsFor(group: MarketGroup): List<PlatformId> = when (group) {
        MarketGroup.GENERAL -> general
        MarketGroup.VEHICLES -> vehicles
        MarketGroup.REAL_ESTATE -> realEstate
    }

    /** The group a platform's chip sits under in the markets picker. A platform in more than one
     *  list (Kleinanzeigen: general and vehicles) picks the first by this priority, so its chip
     *  appears once, not duplicated per group. */
    fun groupOf(platform: PlatformId): MarketGroup = when {
        platform in general -> MarketGroup.GENERAL
        platform in vehicles -> MarketGroup.VEHICLES
        platform in realEstate -> MarketGroup.REAL_ESTATE
        else -> MarketGroup.GENERAL
    }

    /** The country whose listings a market carries, for grouping it under. The home markets carry
     *  no country of their own; AutoScout24's own site spans several. */
    fun countryOf(platform: PlatformId): String = when (platform) {
        PlatformId.AUTOSCOUT24 -> "EU"
        else -> platform.country ?: "DE"
    }
}
