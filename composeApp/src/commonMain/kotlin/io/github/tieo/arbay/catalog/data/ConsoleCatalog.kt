package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.CONSOLES

object ConsoleCatalog {
    val products = listOf(
        // PlayStation
        KnownProduct("PlayStation 5 Pro", CONSOLES, "Sony", "PS5 Pro", "CFI-7016", tags = listOf("playstation", "current-gen"), aliases = listOf("PlayStation 5 Pro"), excludeKeywords = listOf("controller", "spiel", "game")),
        KnownProduct("PlayStation 5 (Slim Disc)", CONSOLES, "Sony", "PS5 Slim Disc", "CFI-2016", tags = listOf("playstation", "current-gen"), aliases = listOf("PlayStation 5 Slim\" disc"), excludeKeywords = listOf("digital", "controller", "spiel")),
        KnownProduct("PlayStation 5 (Slim Digital)", CONSOLES, "Sony", "PS5 Slim Digital", "CFI-2016", tags = listOf("playstation", "current-gen", "digital"), aliases = listOf("PlayStation 5 Slim\" digital"), excludeKeywords = listOf("disc", "controller", "spiel")),
        // Xbox
        KnownProduct("Xbox Series X", CONSOLES, "Microsoft", "Xbox Series X", "RRT-00010", tags = listOf("xbox", "current-gen"), excludeKeywords = listOf("controller", "spiel", "game", "headset")),
        KnownProduct("Xbox Series S", CONSOLES, "Microsoft", "Xbox Series S", "RRS-00010", tags = listOf("xbox", "current-gen", "budget"), excludeKeywords = listOf("controller", "spiel", "game", "headset")),
        // Nintendo
        KnownProduct("Nintendo Switch 2", CONSOLES, "Nintendo", "Nintendo Switch 2", tags = listOf("nintendo", "next-gen", "handheld"), aliases = listOf("Switch 2"), excludeKeywords = listOf("game", "spiel", "controller")),
        KnownProduct("Nintendo Switch OLED", CONSOLES, "Nintendo", "Nintendo Switch\" OLED", "HEG-001", listOf("4902370549539"), tags = listOf("nintendo", "current-gen", "handheld"), excludeKeywords = listOf("Lite", "game", "spiel", "controller")),
        KnownProduct("Nintendo Switch Lite", CONSOLES, "Nintendo", "Nintendo Switch Lite", "HDH-001", listOf("4902370542318"), tags = listOf("nintendo", "handheld", "budget"), excludeKeywords = listOf("game", "spiel", "case")),
        // Valve
        KnownProduct("Steam Deck OLED (512GB)", CONSOLES, "Valve", "Steam Deck\" OLED 512", tags = listOf("valve", "handheld", "pc-gaming"), excludeKeywords = listOf("LCD", "case", "dock")),
        KnownProduct("Steam Deck LCD (256GB)", CONSOLES, "Valve", "Steam Deck\" LCD 256", tags = listOf("valve", "handheld", "pc-gaming", "budget"), excludeKeywords = listOf("OLED", "case", "dock")),
    )
}
