package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/** What kind of thing a platform sells — a search's default markets, and the picker's grouping,
 *  both read this instead of guessing from a platform's name or maintaining their own list. A
 *  platform can belong to more than one (Kleinanzeigen carries both general goods and cars). */
@Serializable
enum class PlatformCategory(val label: String) {
    GENERAL("General marketplaces"),
    CARS("Vehicle sites"),
    REAL_ESTATE("Real estate"),
}

@Serializable
enum class PlatformId(
    val displayName: String,
    val baseUrl: String,
    // Language the platform's own search expects; a cross-border query is translated into it before
    // searching. Defaults to German for the home market.
    val searchLanguage: String = "de",
    // ISO country the platform's listings are in — for the cross-border origin badge. Null = home.
    val country: String? = null,
    // What this platform is, for the picker's grouping and a category-scoped default search.
    val categories: Set<PlatformCategory> = emptySet(),
    // True for a platform that has a category but isn't included in that category's default
    // search yet — kept out of PlatformCategories' default lists without losing its grouping.
    // AUTOSCOUT24_CH: Swiss coverage deferred (see reference_car_country_coverage memory).
    // GEIZHALS: crawler exists but was never added to the general default set.
    val deferredFromDefaults: Boolean = false,
) {
    EBAY_DE("eBay DE", "https://www.ebay.de", categories = setOf(PlatformCategory.GENERAL, PlatformCategory.CARS)),
    EBAY_COM("eBay COM", "https://www.ebay.com", country = "US", categories = setOf(PlatformCategory.GENERAL)),
    EBAY_IT("eBay IT", "https://www.ebay.it", "it", "IT", categories = setOf(PlatformCategory.GENERAL)),
    EBAY_FR("eBay FR", "https://www.ebay.fr", "fr", "FR", categories = setOf(PlatformCategory.GENERAL)),
    EBAY_ES("eBay ES", "https://www.ebay.es", "es", "ES", categories = setOf(PlatformCategory.GENERAL)),
    KLEINANZEIGEN("Kleinanzeigen", "https://www.kleinanzeigen.de", categories = setOf(PlatformCategory.GENERAL, PlatformCategory.CARS)),
    MOBILE_DE("mobile.de", "https://suchen.mobile.de", categories = setOf(PlatformCategory.CARS)),
    AUTOSCOUT24("AutoScout24", "https://www.autoscout24.de", categories = setOf(PlatformCategory.CARS)),
    // Per-country AutoScout24 markets. All crawl the .de front end with a country filter
    // (cy code) and display prices in EUR; each carries its origin for the cross-border badge.
    AUTOSCOUT24_IT("AutoScout24 IT", "https://www.autoscout24.it", country = "IT", categories = setOf(PlatformCategory.CARS)),
    AUTOSCOUT24_FR("AutoScout24 FR", "https://www.autoscout24.fr", country = "FR", categories = setOf(PlatformCategory.CARS)),
    AUTOSCOUT24_ES("AutoScout24 ES", "https://www.autoscout24.es", country = "ES", categories = setOf(PlatformCategory.CARS)),
    AUTOSCOUT24_BE("AutoScout24 BE", "https://www.autoscout24.be", country = "BE", categories = setOf(PlatformCategory.CARS)),
    AUTOSCOUT24_CH("AutoScout24 CH", "https://www.autoscout24.ch", country = "CH", categories = setOf(PlatformCategory.CARS), deferredFromDefaults = true),
    AUTOVIT("Autovit", "https://www.autovit.ro", country = "RO", categories = setOf(PlatformCategory.CARS)),
    RICARDO("ricardo.ch", "https://www.ricardo.ch", country = "CH", categories = setOf(PlatformCategory.GENERAL, PlatformCategory.CARS)),
    SUBITO("Subito", "https://www.subito.it", "it", "IT", categories = setOf(PlatformCategory.GENERAL, PlatformCategory.CARS)),
    TWEEDEHANDS("2dehands", "https://www.2dehands.be", "nl", "BE", categories = setOf(PlatformCategory.GENERAL, PlatformCategory.CARS)),
    AUTOPLIUS("Autoplius", "https://autoplius.lt", country = "LT", categories = setOf(PlatformCategory.CARS)),
    NETTIAUTO("Nettiauto", "https://www.nettiauto.com", country = "FI", categories = setOf(PlatformCategory.CARS)),
    FINN("FINN", "https://www.finn.no", country = "NO", categories = setOf(PlatformCategory.CARS)),
    OLX_PT("OLX PT", "https://www.olx.pt", "pt", "PT", categories = setOf(PlatformCategory.CARS)),
    KUPUJEM("KupujemProdajem", "https://www.kupujemprodajem.com", country = "RS", categories = setOf(PlatformCategory.CARS)),
    VINTED_DE("Vinted DE", "https://www.vinted.de", categories = setOf(PlatformCategory.GENERAL)),
    BACKMARKET_DE("Back Market DE", "https://www.backmarket.de", categories = setOf(PlatformCategory.GENERAL)),
    REBUY("reBuy", "https://www.rebuy.de", categories = setOf(PlatformCategory.GENERAL)),
    REFURBED("Refurbed", "https://www.refurbed.de", categories = setOf(PlatformCategory.GENERAL)),
    IDEALO("Idealo", "https://www.idealo.de", categories = setOf(PlatformCategory.GENERAL)),
    GEIZHALS("Geizhals", "https://geizhals.de", categories = setOf(PlatformCategory.GENERAL), deferredFromDefaults = true),
    AMAZON_DE("Amazon DE", "https://www.amazon.de", categories = setOf(PlatformCategory.GENERAL)),
    WILLHABEN("willhaben", "https://www.willhaben.at", country = "AT", categories = setOf(PlatformCategory.GENERAL, PlatformCategory.CARS)),
    MARKTPLAATS("Marktplaats", "https://www.marktplaats.nl", "nl", "NL", categories = setOf(PlatformCategory.GENERAL, PlatformCategory.CARS)),
    IMMOSCOUT24("ImmobilienScout24", "https://www.immobilienscout24.de", categories = setOf(PlatformCategory.REAL_ESTATE)),
    TRUCKSCOUT24("TruckScout24", "https://www.truckscout24.de", categories = setOf(PlatformCategory.CARS)),
    OTOMOTO("OTOMoto", "https://www.otomoto.pl", country = "PL", categories = setOf(PlatformCategory.CARS)),
    DBA("DBA", "https://www.dba.dk", country = "DK", categories = setOf(PlatformCategory.CARS)),
    BILBASEN("Bilbasen", "https://www.bilbasen.dk", country = "DK", categories = setOf(PlatformCategory.CARS)),
    BYTBIL("Bytbil", "https://www.bytbil.com", country = "SE", categories = setOf(PlatformCategory.CARS)),
    SAUTO("Sauto", "https://www.sauto.cz", country = "CZ", categories = setOf(PlatformCategory.CARS)),
}
