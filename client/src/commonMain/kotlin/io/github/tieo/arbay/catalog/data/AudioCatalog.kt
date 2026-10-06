package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.AUDIO

object AudioCatalog {
    val products = listOf(
        // Sonos
        KnownProduct("Sonos Era 300", AUDIO, "Sonos", "Sonos Era 300", "E30G1", tags = listOf("smart-speaker", "spatial-audio", "wifi"), excludeKeywords = listOf("mount", "stand")),
        KnownProduct("Sonos Era 100", AUDIO, "Sonos", "Sonos Era 100", "E10G1", tags = listOf("smart-speaker", "wifi"), excludeKeywords = listOf("mount", "stand")),
        KnownProduct("Sonos Arc", AUDIO, "Sonos", "Sonos Arc", "ARCG1", tags = listOf("soundbar", "dolby-atmos", "wifi"), excludeKeywords = listOf("mount", "wall", "Sub")),
        KnownProduct("Sonos Sub (Gen 3)", AUDIO, "Sonos", "Sonos Sub Gen 3", "SUBG3", tags = listOf("subwoofer", "wifi"), aliases = listOf("Sonos Sub"), excludeKeywords = listOf("Mini")),
        // JBL
        KnownProduct("JBL Charge 5", AUDIO, "JBL", "JBL Charge 5", "JBLCHARGE5", listOf("6925281982002"), tags = listOf("bluetooth-speaker", "portable", "waterproof"), excludeKeywords = listOf("case")),
        KnownProduct("JBL Flip 6", AUDIO, "JBL", "JBL Flip 6", "JBLFLIP6", listOf("6925281993091"), tags = listOf("bluetooth-speaker", "portable", "waterproof"), excludeKeywords = listOf("case")),
        KnownProduct("JBL Xtreme 4", AUDIO, "JBL", "JBL Xtreme 4", "JBLXTREME4", tags = listOf("bluetooth-speaker", "portable", "waterproof", "party"), excludeKeywords = listOf("case", "strap")),
        // Bose
        KnownProduct("Bose SoundLink Max", AUDIO, "Bose", "Bose SoundLink Max", tags = listOf("bluetooth-speaker", "portable"), excludeKeywords = listOf("case")),
        KnownProduct("Bose SoundLink Flex", AUDIO, "Bose", "Bose SoundLink Flex", "865983-0100", tags = listOf("bluetooth-speaker", "portable", "waterproof"), excludeKeywords = listOf("case")),
        // Marshall
        KnownProduct("Marshall Stanmore III", AUDIO, "Marshall", "Marshall Stanmore III", tags = listOf("bluetooth-speaker", "home", "retro"), excludeKeywords = listOf("case")),
        // Apple
        KnownProduct("Apple HomePod (2nd Gen)", AUDIO, "Apple", "HomePod 2", "MQJ73", tags = listOf("smart-speaker", "wifi"), aliases = listOf("HomePod 2nd"), excludeKeywords = listOf("mini", "mount")),
    )
}
