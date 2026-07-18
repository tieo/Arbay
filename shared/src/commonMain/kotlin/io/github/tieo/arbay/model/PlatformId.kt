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
