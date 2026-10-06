package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.TABLETS

object TabletsCatalog {
    val products = listOf(
        // Apple
        KnownProduct("iPad Pro M4 13\"", TABLETS, "Apple", "iPad Pro\" M4 13", "MW6C3", tags = listOf("ios", "flagship", "oled"), excludeKeywords = listOf("huelle", "case", "folie", "pencil")),
        KnownProduct("iPad Pro M4 11\"", TABLETS, "Apple", "iPad Pro\" M4 11", "MW5L3", tags = listOf("ios", "flagship", "oled"), excludeKeywords = listOf("huelle", "case", "folie", "pencil")),
        KnownProduct("iPad Air M2 13\"", TABLETS, "Apple", "iPad Air\" M2 13", "MW813", tags = listOf("ios", "midrange"), excludeKeywords = listOf("huelle", "case", "folie", "pencil")),
        KnownProduct("iPad Air M2 11\"", TABLETS, "Apple", "iPad Air\" M2 11", "MW7E3", tags = listOf("ios", "midrange"), excludeKeywords = listOf("huelle", "case", "folie", "pencil")),
        KnownProduct("iPad 10th Gen", TABLETS, "Apple", "iPad 10 Generation", "MPQ03", tags = listOf("ios", "budget"), aliases = listOf("iPad 10th"), excludeKeywords = listOf("huelle", "case", "folie", "Pro", "Air")),
        KnownProduct("iPad mini (A17 Pro)", TABLETS, "Apple", "iPad mini\" A17", tags = listOf("ios", "compact"), aliases = listOf("iPad mini 7"), excludeKeywords = listOf("huelle", "case", "folie")),
        // Samsung
        KnownProduct("Samsung Galaxy Tab S10 Ultra", TABLETS, "Samsung", "Galaxy Tab S10 Ultra", "SM-X920", tags = listOf("android", "flagship"), excludeKeywords = listOf("huelle", "case", "folie", "keyboard")),
        KnownProduct("Samsung Galaxy Tab S9", TABLETS, "Samsung", "Galaxy Tab S9", "SM-X710", tags = listOf("android", "flagship"), excludeKeywords = listOf("Ultra", "Plus", "FE", "huelle", "case", "folie")),
        KnownProduct("Samsung Galaxy Tab S9 FE", TABLETS, "Samsung", "Galaxy Tab S9 FE", "SM-X510", tags = listOf("android", "midrange"), excludeKeywords = listOf("Plus", "Ultra", "huelle", "case")),
        // Lenovo
        KnownProduct("Lenovo Tab P12 Pro", TABLETS, "Lenovo", "Lenovo Tab P12 Pro", tags = listOf("android", "midrange"), excludeKeywords = listOf("huelle", "case")),
    )
}
