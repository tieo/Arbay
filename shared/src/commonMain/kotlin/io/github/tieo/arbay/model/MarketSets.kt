package io.github.tieo.arbay.model

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

    /** The country whose listings a market carries, for grouping it under. The home markets carry
     *  no country of their own; AutoScout24's own site spans several. */
    fun countryOf(platform: PlatformId): String = when (platform) {
        PlatformId.AUTOSCOUT24 -> "EU"
        else -> platform.country ?: "DE"
    }
}
