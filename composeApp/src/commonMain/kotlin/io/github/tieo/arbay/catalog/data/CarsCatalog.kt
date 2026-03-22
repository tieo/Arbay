package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.CARS

object CarsCatalog {
    val products = listOf(
        // VW
        KnownProduct("VW Golf 8", CARS, "Volkswagen", "Golf 8 OR Golf VIII", tags = listOf("compact", "hatchback")),
        KnownProduct("VW Golf 7", CARS, "Volkswagen", "Golf 7 OR Golf VII -8 -VIII", tags = listOf("compact", "hatchback")),
        KnownProduct("VW Polo 6R/AW", CARS, "Volkswagen", "VW Polo 6R OR Polo AW", tags = listOf("subcompact", "hatchback")),
        // BMW
        KnownProduct("BMW 3er (G20)", CARS, "BMW", "BMW 3er G20 OR 320i G20 OR 330i G20 OR 320d G20", tags = listOf("sedan", "premium")),
        KnownProduct("BMW 5er (G30/G60)", CARS, "BMW", "BMW 5er G30 OR G60 OR 520i OR 530i OR 520d", tags = listOf("sedan", "premium")),
        // Mercedes
        KnownProduct("Mercedes C-Klasse (W206)", CARS, "Mercedes-Benz", "Mercedes C-Klasse W206 OR C200 W206 OR C220 W206", tags = listOf("sedan", "premium")),
        KnownProduct("Mercedes A-Klasse (W177)", CARS, "Mercedes-Benz", "Mercedes A-Klasse W177 OR A200 W177 OR A180 W177", tags = listOf("compact", "premium")),
        // Tesla
        KnownProduct("Tesla Model 3", CARS, "Tesla", "\"Tesla Model 3\" -miniatur -modell -spielzeug", tags = listOf("electric", "sedan")),
        KnownProduct("Tesla Model Y", CARS, "Tesla", "\"Tesla Model Y\" -miniatur -modell -spielzeug", tags = listOf("electric", "suv")),
        // Audi
        KnownProduct("Audi A4 (B9)", CARS, "Audi", "Audi A4 B9 OR A4 Avant B9", tags = listOf("sedan", "wagon", "premium")),
    )
}
