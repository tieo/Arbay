package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
enum class PlatformId(val displayName: String, val baseUrl: String) {
    EBAY_DE("eBay DE", "https://www.ebay.de"),
    EBAY_COM("eBay COM", "https://www.ebay.com"),
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
    WILLHABEN("willhaben", "https://www.willhaben.at"),
    MARKTPLAATS("Marktplaats", "https://www.marktplaats.nl"),
    IMMOSCOUT24("ImmobilienScout24", "https://www.immobilienscout24.de"),
    TRUCKSCOUT24("TruckScout24", "https://www.truckscout24.de"),
    OTOMOTO("OTOMoto", "https://www.otomoto.pl"),
    DBA("DBA", "https://www.dba.dk"),
    BILBASEN("Bilbasen", "https://www.bilbasen.dk"),
    BYTBIL("Bytbil", "https://www.bytbil.com"),
    SAUTO("Sauto", "https://www.sauto.cz"),
}
