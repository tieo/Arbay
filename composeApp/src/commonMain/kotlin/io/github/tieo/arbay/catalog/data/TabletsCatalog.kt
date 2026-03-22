package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.TABLETS

object TabletsCatalog {
    val products = listOf(
        // Apple
        KnownProduct("iPad Pro M4 13\"", TABLETS, "Apple", "\"iPad Pro\" M4 13 -huelle -case -folie -pencil", "MW6C3", tags = listOf("ios", "flagship", "oled")),
        KnownProduct("iPad Pro M4 11\"", TABLETS, "Apple", "\"iPad Pro\" M4 11 -huelle -case -folie -pencil", "MW5L3", tags = listOf("ios", "flagship", "oled")),
        KnownProduct("iPad Air M2 13\"", TABLETS, "Apple", "\"iPad Air\" M2 13 -huelle -case -folie -pencil", "MW813", tags = listOf("ios", "midrange")),
        KnownProduct("iPad Air M2 11\"", TABLETS, "Apple", "\"iPad Air\" M2 11 -huelle -case -folie -pencil", "MW7E3", tags = listOf("ios", "midrange")),
        KnownProduct("iPad 10th Gen", TABLETS, "Apple", "iPad 10 Generation OR \"iPad 10th\" -huelle -case -folie -Pro -Air", "MPQ03", tags = listOf("ios", "budget")),
        KnownProduct("iPad mini (A17 Pro)", TABLETS, "Apple", "\"iPad mini\" A17 OR \"iPad mini 7\" -huelle -case -folie", tags = listOf("ios", "compact")),
        // Samsung
        KnownProduct("Samsung Galaxy Tab S10 Ultra", TABLETS, "Samsung", "Galaxy Tab S10 Ultra -huelle -case -folie -keyboard", "SM-X920", tags = listOf("android", "flagship")),
        KnownProduct("Samsung Galaxy Tab S9", TABLETS, "Samsung", "Galaxy Tab S9 -Ultra -Plus -FE -huelle -case -folie", "SM-X710", tags = listOf("android", "flagship")),
        KnownProduct("Samsung Galaxy Tab S9 FE", TABLETS, "Samsung", "Galaxy Tab S9 FE -Plus -Ultra -huelle -case", "SM-X510", tags = listOf("android", "midrange")),
        // Lenovo
        KnownProduct("Lenovo Tab P12 Pro", TABLETS, "Lenovo", "Lenovo Tab P12 Pro -huelle -case", tags = listOf("android", "midrange")),
    )
}
