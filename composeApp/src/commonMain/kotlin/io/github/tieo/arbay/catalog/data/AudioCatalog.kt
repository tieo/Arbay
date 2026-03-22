package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.AUDIO

object AudioCatalog {
    val products = listOf(
        // Sonos
        KnownProduct("Sonos Era 300", AUDIO, "Sonos", "Sonos Era 300 -mount -stand", "E30G1", tags = listOf("smart-speaker", "spatial-audio", "wifi")),
        KnownProduct("Sonos Era 100", AUDIO, "Sonos", "Sonos Era 100 -mount -stand", "E10G1", tags = listOf("smart-speaker", "wifi")),
        KnownProduct("Sonos Arc", AUDIO, "Sonos", "Sonos Arc -mount -wall -Sub", "ARCG1", tags = listOf("soundbar", "dolby-atmos", "wifi")),
        KnownProduct("Sonos Sub (Gen 3)", AUDIO, "Sonos", "Sonos Sub Gen 3 OR \"Sonos Sub\" -Mini", "SUBG3", tags = listOf("subwoofer", "wifi")),
        // JBL
        KnownProduct("JBL Charge 5", AUDIO, "JBL", "JBL Charge 5 -case", "JBLCHARGE5", listOf("6925281982002"), tags = listOf("bluetooth-speaker", "portable", "waterproof")),
        KnownProduct("JBL Flip 6", AUDIO, "JBL", "JBL Flip 6 -case", "JBLFLIP6", listOf("6925281993091"), tags = listOf("bluetooth-speaker", "portable", "waterproof")),
        KnownProduct("JBL Xtreme 4", AUDIO, "JBL", "JBL Xtreme 4 -case -strap", "JBLXTREME4", tags = listOf("bluetooth-speaker", "portable", "waterproof", "party")),
        // Bose
        KnownProduct("Bose SoundLink Max", AUDIO, "Bose", "Bose SoundLink Max -case", tags = listOf("bluetooth-speaker", "portable")),
        KnownProduct("Bose SoundLink Flex", AUDIO, "Bose", "Bose SoundLink Flex -case", "865983-0100", tags = listOf("bluetooth-speaker", "portable", "waterproof")),
        // Marshall
        KnownProduct("Marshall Stanmore III", AUDIO, "Marshall", "Marshall Stanmore III -case", tags = listOf("bluetooth-speaker", "home", "retro")),
        // Apple
        KnownProduct("Apple HomePod (2nd Gen)", AUDIO, "Apple", "HomePod 2 OR \"HomePod 2nd\" -mini -mount", "MQJ73", tags = listOf("smart-speaker", "wifi")),
    )
}
