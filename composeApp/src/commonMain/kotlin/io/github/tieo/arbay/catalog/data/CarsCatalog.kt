package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.CARS

object CarsCatalog {
    val products = listOf(
        // VW
        KnownProduct("VW Golf 8", CARS, "Volkswagen", "Golf 8", tags = listOf("compact", "hatchback"), aliases = listOf("Golf VIII")),
        KnownProduct("VW Golf 7", CARS, "Volkswagen", "Golf 7", tags = listOf("compact", "hatchback"), aliases = listOf("Golf VII"), excludeKeywords = listOf("8", "VIII")),
        KnownProduct("VW Polo 6R/AW", CARS, "Volkswagen", "VW Polo 6R", tags = listOf("subcompact", "hatchback"), aliases = listOf("Polo AW")),
        // BMW
        KnownProduct("BMW 3er (G20)", CARS, "BMW", "BMW 3er G20", tags = listOf("sedan", "premium"), aliases = listOf("320i G20", "330i G20", "320d G20")),
        KnownProduct("BMW 5er (G30/G60)", CARS, "BMW", "BMW 5er G30", tags = listOf("sedan", "premium"), aliases = listOf("G60", "520i", "530i", "520d")),
        // Mercedes
        KnownProduct("Mercedes C-Klasse (W206)", CARS, "Mercedes-Benz", "Mercedes C-Klasse W206", tags = listOf("sedan", "premium"), aliases = listOf("C200 W206", "C220 W206")),
        KnownProduct("Mercedes A-Klasse (W177)", CARS, "Mercedes-Benz", "Mercedes A-Klasse W177", tags = listOf("compact", "premium"), aliases = listOf("A200 W177", "A180 W177")),
        // Tesla
        KnownProduct("Tesla Model 3", CARS, "Tesla", "Tesla Model 3", tags = listOf("electric", "sedan"), excludeKeywords = listOf("miniatur", "modell", "spielzeug")),
        KnownProduct("Tesla Model Y", CARS, "Tesla", "Tesla Model Y", tags = listOf("electric", "suv"), excludeKeywords = listOf("miniatur", "modell", "spielzeug")),
        // Audi
        KnownProduct("Audi A4 (B9)", CARS, "Audi", "Audi A4 B9", tags = listOf("sedan", "wagon", "premium"), aliases = listOf("A4 Avant B9")),
    )
}
