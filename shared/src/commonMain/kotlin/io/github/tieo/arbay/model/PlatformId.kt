package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlatformId(
    val displayName: String,
    val baseUrl: String,
    // Language the platform's own search expects; a cross-border query is translated into it before
    // searching. Defaults to German for the home market.
    val searchLanguage: String = "de",
    // ISO country the platform's listings are in — for the cross-border origin badge. Null = home.
    val country: String? = null,
) {
    EBAY_DE("eBay DE", "https://www.ebay.de"),
    EBAY_COM("eBay COM", "https://www.ebay.com", country = "US"),
    EBAY_IT("eBay IT", "https://www.ebay.it", "it", "IT"),
    EBAY_FR("eBay FR", "https://www.ebay.fr", "fr", "FR"),
    EBAY_ES("eBay ES", "https://www.ebay.es", "es", "ES"),
    KLEINANZEIGEN("Kleinanzeigen", "https://www.kleinanzeigen.de"),
    MOBILE_DE("mobile.de", "https://suchen.mobile.de"),
    AUTOSCOUT24("AutoScout24", "https://www.autoscout24.de"),
    // Per-country AutoScout24 markets. All crawl the .de front end with a country filter
    // (cy code) and display prices in EUR; each carries its origin for the cross-border badge.
    AUTOSCOUT24_IT("AutoScout24 IT", "https://www.autoscout24.it", country = "IT"),
    AUTOSCOUT24_FR("AutoScout24 FR", "https://www.autoscout24.fr", country = "FR"),
    AUTOSCOUT24_ES("AutoScout24 ES", "https://www.autoscout24.es", country = "ES"),
    AUTOSCOUT24_BE("AutoScout24 BE", "https://www.autoscout24.be", country = "BE"),
    AUTOSCOUT24_CH("AutoScout24 CH", "https://www.autoscout24.ch", country = "CH"),
    AUTOVIT("Autovit", "https://www.autovit.ro", country = "RO"),
    RICARDO("ricardo.ch", "https://www.ricardo.ch", country = "CH"),
    SUBITO("Subito", "https://www.subito.it", "it", "IT"),
    VINTED_DE("Vinted DE", "https://www.vinted.de"),
    BACKMARKET_DE("Back Market DE", "https://www.backmarket.de"),
    REBUY("reBuy", "https://www.rebuy.de"),
    REFURBED("Refurbed", "https://www.refurbed.de"),
    IDEALO("Idealo", "https://www.idealo.de"),
    GEIZHALS("Geizhals", "https://geizhals.de"),
    AMAZON_DE("Amazon DE", "https://www.amazon.de"),
    WILLHABEN("willhaben", "https://www.willhaben.at", country = "AT"),
    MARKTPLAATS("Marktplaats", "https://www.marktplaats.nl", country = "NL"),
    IMMOSCOUT24("ImmobilienScout24", "https://www.immobilienscout24.de"),
    TRUCKSCOUT24("TruckScout24", "https://www.truckscout24.de"),
    OTOMOTO("OTOMoto", "https://www.otomoto.pl", country = "PL"),
    DBA("DBA", "https://www.dba.dk", country = "DK"),
    BILBASEN("Bilbasen", "https://www.bilbasen.dk", country = "DK"),
    BYTBIL("Bytbil", "https://www.bytbil.com", country = "SE"),
    SAUTO("Sauto", "https://www.sauto.cz", country = "CZ"),
}
