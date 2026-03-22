package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.CONSOLES

object ConsoleCatalog {
    val products = listOf(
        // PlayStation
        KnownProduct("PlayStation 5 Pro", CONSOLES, "Sony", "PS5 Pro OR \"PlayStation 5 Pro\" -controller -spiel -game", "CFI-7016", tags = listOf("playstation", "current-gen")),
        KnownProduct("PlayStation 5 (Slim Disc)", CONSOLES, "Sony", "PS5 Slim Disc OR \"PlayStation 5 Slim\" disc -digital -controller -spiel", "CFI-2016", tags = listOf("playstation", "current-gen")),
        KnownProduct("PlayStation 5 (Slim Digital)", CONSOLES, "Sony", "PS5 Slim Digital OR \"PlayStation 5 Slim\" digital -disc -controller -spiel", "CFI-2016", tags = listOf("playstation", "current-gen", "digital")),
        // Xbox
        KnownProduct("Xbox Series X", CONSOLES, "Microsoft", "Xbox Series X -controller -spiel -game -headset", "RRT-00010", tags = listOf("xbox", "current-gen")),
        KnownProduct("Xbox Series S", CONSOLES, "Microsoft", "Xbox Series S -controller -spiel -game -headset", "RRS-00010", tags = listOf("xbox", "current-gen", "budget")),
        // Nintendo
        KnownProduct("Nintendo Switch 2", CONSOLES, "Nintendo", "\"Nintendo Switch 2\" OR \"Switch 2\" -game -spiel -controller", tags = listOf("nintendo", "next-gen", "handheld")),
        KnownProduct("Nintendo Switch OLED", CONSOLES, "Nintendo", "\"Nintendo Switch\" OLED -Lite -game -spiel -controller", "HEG-001", listOf("4902370549539"), tags = listOf("nintendo", "current-gen", "handheld")),
        KnownProduct("Nintendo Switch Lite", CONSOLES, "Nintendo", "\"Nintendo Switch Lite\" -game -spiel -case", "HDH-001", listOf("4902370542318"), tags = listOf("nintendo", "handheld", "budget")),
        // Valve
        KnownProduct("Steam Deck OLED (512GB)", CONSOLES, "Valve", "\"Steam Deck\" OLED 512 -LCD -case -dock", tags = listOf("valve", "handheld", "pc-gaming")),
        KnownProduct("Steam Deck LCD (256GB)", CONSOLES, "Valve", "\"Steam Deck\" LCD 256 -OLED -case -dock", tags = listOf("valve", "handheld", "pc-gaming", "budget")),
    )
}
