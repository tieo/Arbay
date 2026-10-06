package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.SMARTPHONES

object SmartphonesCatalog {
    val products = listOf(
        // Apple
        KnownProduct("iPhone 16 Pro Max", SMARTPHONES, "Apple", "iPhone 16 Pro Max", "A3106", tags = listOf("ios", "flagship"), excludeKeywords = listOf("huelle", "case", "folie")),
        KnownProduct("iPhone 16 Pro", SMARTPHONES, "Apple", "iPhone 16 Pro", "A3101", tags = listOf("ios", "flagship"), excludeKeywords = listOf("huelle", "case", "folie")),
        KnownProduct("iPhone 16", SMARTPHONES, "Apple", "iPhone 16", "A3287", tags = listOf("ios"), excludeKeywords = listOf("Pro", "Plus", "16e", "huelle", "case", "folie")),
        KnownProduct("iPhone 15 Pro Max", SMARTPHONES, "Apple", "iPhone 15 Pro Max", "A2849", tags = listOf("ios", "flagship"), excludeKeywords = listOf("huelle", "case", "folie")),
        KnownProduct("iPhone 15 Pro", SMARTPHONES, "Apple", "iPhone 15 Pro", "A2848", tags = listOf("ios", "flagship"), excludeKeywords = listOf("Max", "huelle", "case", "folie")),
        KnownProduct("iPhone 15", SMARTPHONES, "Apple", "iPhone 15", "A2846", tags = listOf("ios"), excludeKeywords = listOf("Pro", "Plus", "huelle", "case", "folie")),
        KnownProduct("iPhone 14 Pro Max", SMARTPHONES, "Apple", "iPhone 14 Pro Max", "A2894", tags = listOf("ios", "flagship"), excludeKeywords = listOf("huelle", "case", "folie")),
        KnownProduct("iPhone 14", SMARTPHONES, "Apple", "iPhone 14", "A2882", tags = listOf("ios"), excludeKeywords = listOf("Pro", "Plus", "huelle", "case", "folie")),
        KnownProduct("iPhone SE (2022)", SMARTPHONES, "Apple", "iPhone SE 2022", "A2783", tags = listOf("ios", "budget"), aliases = listOf("iPhone SE 3"), excludeKeywords = listOf("huelle", "case")),
        // Samsung
        KnownProduct("Samsung Galaxy S25 Ultra", SMARTPHONES, "Samsung", "Galaxy S25 Ultra", "SM-S938B", tags = listOf("android", "flagship"), excludeKeywords = listOf("huelle", "case", "folie")),
        KnownProduct("Samsung Galaxy S25", SMARTPHONES, "Samsung", "Galaxy S25", "SM-S931B", tags = listOf("android", "flagship"), excludeKeywords = listOf("Ultra", "Plus", "FE", "huelle", "case")),
        KnownProduct("Samsung Galaxy S24 Ultra", SMARTPHONES, "Samsung", "Galaxy S24 Ultra", "SM-S928B", tags = listOf("android", "flagship"), excludeKeywords = listOf("huelle", "case", "folie")),
        KnownProduct("Samsung Galaxy S24", SMARTPHONES, "Samsung", "Galaxy S24", "SM-S921B", tags = listOf("android", "flagship"), excludeKeywords = listOf("Ultra", "Plus", "FE", "huelle", "case")),
        KnownProduct("Samsung Galaxy S23 Ultra", SMARTPHONES, "Samsung", "Galaxy S23 Ultra", "SM-S918B", tags = listOf("android", "flagship"), excludeKeywords = listOf("huelle", "case", "folie")),
        KnownProduct("Samsung Galaxy Z Fold6", SMARTPHONES, "Samsung", "Galaxy Z Fold6", "SM-F956B", tags = listOf("android", "foldable"), excludeKeywords = listOf("huelle", "case")),
        KnownProduct("Samsung Galaxy Z Flip6", SMARTPHONES, "Samsung", "Galaxy Z Flip6", "SM-F741B", tags = listOf("android", "foldable"), excludeKeywords = listOf("huelle", "case")),
        KnownProduct("Samsung Galaxy A55", SMARTPHONES, "Samsung", "Galaxy A55", "SM-A556B", tags = listOf("android", "midrange"), excludeKeywords = listOf("huelle", "case", "folie")),
        // Google
        KnownProduct("Google Pixel 9 Pro", SMARTPHONES, "Google", "Pixel 9 Pro", "GA05928", tags = listOf("android", "flagship"), excludeKeywords = listOf("case", "huelle", "folie")),
        KnownProduct("Google Pixel 9", SMARTPHONES, "Google", "Pixel 9", "GA05524", tags = listOf("android"), excludeKeywords = listOf("Pro", "case", "huelle")),
        KnownProduct("Google Pixel 8 Pro", SMARTPHONES, "Google", "Pixel 8 Pro", "GA04834", tags = listOf("android", "flagship"), excludeKeywords = listOf("case", "huelle", "folie")),
        KnownProduct("Google Pixel 8", SMARTPHONES, "Google", "Pixel 8", "GA04831", tags = listOf("android"), excludeKeywords = listOf("Pro", "case", "huelle")),
        KnownProduct("Google Pixel 8a", SMARTPHONES, "Google", "Pixel 8a", "GA05329", tags = listOf("android", "budget"), excludeKeywords = listOf("case", "huelle")),
        // Others
        KnownProduct("Xiaomi 14", SMARTPHONES, "Xiaomi", "Xiaomi 14", "2312DRA50G", tags = listOf("android"), excludeKeywords = listOf("Ultra", "Pro", "case", "huelle")),
        KnownProduct("OnePlus 12", SMARTPHONES, "OnePlus", "OnePlus 12", "CPH2583", tags = listOf("android", "flagship"), excludeKeywords = listOf("case", "huelle", "folie")),
        KnownProduct("Nothing Phone (2)", SMARTPHONES, "Nothing", "Nothing Phone 2", "A065", tags = listOf("android"), excludeKeywords = listOf("case", "huelle")),
    )
}
