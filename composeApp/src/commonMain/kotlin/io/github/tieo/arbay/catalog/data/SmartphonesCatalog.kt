package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.SMARTPHONES

object SmartphonesCatalog {
    val products = listOf(
        // Apple
        KnownProduct("iPhone 16 Pro Max", SMARTPHONES, "Apple", "iPhone 16 Pro Max -huelle -case -folie", "A3106", tags = listOf("ios", "flagship")),
        KnownProduct("iPhone 16 Pro", SMARTPHONES, "Apple", "iPhone 16 Pro -huelle -case -folie", "A3101", tags = listOf("ios", "flagship")),
        KnownProduct("iPhone 16", SMARTPHONES, "Apple", "iPhone 16 -Pro -Plus -16e -huelle -case -folie", "A3287", tags = listOf("ios")),
        KnownProduct("iPhone 15 Pro Max", SMARTPHONES, "Apple", "iPhone 15 Pro Max -huelle -case -folie", "A2849", tags = listOf("ios", "flagship")),
        KnownProduct("iPhone 15 Pro", SMARTPHONES, "Apple", "iPhone 15 Pro -Max -huelle -case -folie", "A2848", tags = listOf("ios", "flagship")),
        KnownProduct("iPhone 15", SMARTPHONES, "Apple", "iPhone 15 -Pro -Plus -huelle -case -folie", "A2846", tags = listOf("ios")),
        KnownProduct("iPhone 14 Pro Max", SMARTPHONES, "Apple", "iPhone 14 Pro Max -huelle -case -folie", "A2894", tags = listOf("ios", "flagship")),
        KnownProduct("iPhone 14", SMARTPHONES, "Apple", "iPhone 14 -Pro -Plus -huelle -case -folie", "A2882", tags = listOf("ios")),
        KnownProduct("iPhone SE (2022)", SMARTPHONES, "Apple", "iPhone SE 2022 OR \"iPhone SE 3\" -huelle -case", "A2783", tags = listOf("ios", "budget")),
        // Samsung
        KnownProduct("Samsung Galaxy S25 Ultra", SMARTPHONES, "Samsung", "Galaxy S25 Ultra -huelle -case -folie", "SM-S938B", tags = listOf("android", "flagship")),
        KnownProduct("Samsung Galaxy S25", SMARTPHONES, "Samsung", "Galaxy S25 -Ultra -Plus -FE -huelle -case", "SM-S931B", tags = listOf("android", "flagship")),
        KnownProduct("Samsung Galaxy S24 Ultra", SMARTPHONES, "Samsung", "Galaxy S24 Ultra -huelle -case -folie", "SM-S928B", tags = listOf("android", "flagship")),
        KnownProduct("Samsung Galaxy S24", SMARTPHONES, "Samsung", "Galaxy S24 -Ultra -Plus -FE -huelle -case", "SM-S921B", tags = listOf("android", "flagship")),
        KnownProduct("Samsung Galaxy S23 Ultra", SMARTPHONES, "Samsung", "Galaxy S23 Ultra -huelle -case -folie", "SM-S918B", tags = listOf("android", "flagship")),
        KnownProduct("Samsung Galaxy Z Fold6", SMARTPHONES, "Samsung", "Galaxy Z Fold6 -huelle -case", "SM-F956B", tags = listOf("android", "foldable")),
        KnownProduct("Samsung Galaxy Z Flip6", SMARTPHONES, "Samsung", "Galaxy Z Flip6 -huelle -case", "SM-F741B", tags = listOf("android", "foldable")),
        KnownProduct("Samsung Galaxy A55", SMARTPHONES, "Samsung", "Galaxy A55 -huelle -case -folie", "SM-A556B", tags = listOf("android", "midrange")),
        // Google
        KnownProduct("Google Pixel 9 Pro", SMARTPHONES, "Google", "Pixel 9 Pro -case -huelle -folie", "GA05928", tags = listOf("android", "flagship")),
        KnownProduct("Google Pixel 9", SMARTPHONES, "Google", "Pixel 9 -Pro -case -huelle", "GA05524", tags = listOf("android")),
        KnownProduct("Google Pixel 8 Pro", SMARTPHONES, "Google", "Pixel 8 Pro -case -huelle -folie", "GA04834", tags = listOf("android", "flagship")),
        KnownProduct("Google Pixel 8", SMARTPHONES, "Google", "Pixel 8 -Pro -case -huelle", "GA04831", tags = listOf("android")),
        KnownProduct("Google Pixel 8a", SMARTPHONES, "Google", "Pixel 8a -case -huelle", "GA05329", tags = listOf("android", "budget")),
        // Others
        KnownProduct("Xiaomi 14", SMARTPHONES, "Xiaomi", "Xiaomi 14 -Ultra -Pro -case -huelle", "2312DRA50G", tags = listOf("android")),
        KnownProduct("OnePlus 12", SMARTPHONES, "OnePlus", "OnePlus 12 -case -huelle -folie", "CPH2583", tags = listOf("android", "flagship")),
        KnownProduct("Nothing Phone (2)", SMARTPHONES, "Nothing", "Nothing Phone 2 -case -huelle", "A065", tags = listOf("android")),
    )
}
