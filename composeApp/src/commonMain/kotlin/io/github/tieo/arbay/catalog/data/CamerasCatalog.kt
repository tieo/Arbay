package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.CAMERAS

object CamerasCatalog {
    val products = listOf(
        // Sony
        KnownProduct("Sony A7 IV", CAMERAS, "Sony", "Sony A7 IV OR A7IV OR ILCE-7M4 -lens -cage -rig", "ILCE-7M4", listOf("4548736128712"), tags = listOf("mirrorless", "full-frame")),
        KnownProduct("Sony A7C II", CAMERAS, "Sony", "Sony A7C II OR A7CII OR ILCE-7CM2 -lens -cage", "ILCE-7CM2", tags = listOf("mirrorless", "full-frame", "compact")),
        KnownProduct("Sony A6700", CAMERAS, "Sony", "Sony A6700 OR ILCE-6700 -lens -cage -rig", "ILCE-6700", listOf("4548736150225"), tags = listOf("mirrorless", "aps-c")),
        KnownProduct("Sony ZV-E10 II", CAMERAS, "Sony", "Sony ZV-E10 II OR ZV-E10M2 -lens -cage", "ZV-E10M2", tags = listOf("mirrorless", "aps-c", "vlog")),
        // Canon
        KnownProduct("Canon EOS R6 Mark II", CAMERAS, "Canon", "Canon R6 Mark II OR R6II OR \"EOS R6 II\" -lens -cage", "EOS R6 II", listOf("4549292200348"), tags = listOf("mirrorless", "full-frame")),
        KnownProduct("Canon EOS R8", CAMERAS, "Canon", "Canon EOS R8 -lens -cage -grip", "EOS R8", listOf("4549292207743"), tags = listOf("mirrorless", "full-frame", "compact")),
        KnownProduct("Canon EOS R50", CAMERAS, "Canon", "Canon EOS R50 -lens -cage", "EOS R50", listOf("4549292207071"), tags = listOf("mirrorless", "aps-c", "entry")),
        // Nikon
        KnownProduct("Nikon Z8", CAMERAS, "Nikon", "Nikon Z8 -lens -cage -grip -rig", "Z8", listOf("4960759910295"), tags = listOf("mirrorless", "full-frame", "flagship")),
        KnownProduct("Nikon Z6 III", CAMERAS, "Nikon", "Nikon Z6 III OR Z6III -lens -cage", "Z6 III", tags = listOf("mirrorless", "full-frame")),
        // Fujifilm
        KnownProduct("Fujifilm X-T5", CAMERAS, "Fujifilm", "Fujifilm X-T5 OR XT5 -lens -cage -grip", "X-T5", listOf("4547410467376"), tags = listOf("mirrorless", "aps-c", "retro")),
        KnownProduct("Fujifilm X-S20", CAMERAS, "Fujifilm", "Fujifilm X-S20 OR XS20 -lens -cage", "X-S20", listOf("4547410473964"), tags = listOf("mirrorless", "aps-c", "vlog")),
        KnownProduct("Fujifilm X100VI", CAMERAS, "Fujifilm", "Fujifilm X100VI OR X100 VI -case -filter", "X100VI", tags = listOf("compact", "aps-c", "fixed-lens", "retro")),
        // GoPro
        KnownProduct("GoPro HERO13 Black", CAMERAS, "GoPro", "GoPro HERO13 OR Hero 13 -mount -case -accessory", "CHDHX-131", tags = listOf("action-cam", "waterproof")),
        KnownProduct("GoPro HERO12 Black", CAMERAS, "GoPro", "GoPro HERO12 OR Hero 12 -mount -case -accessory", "CHDHX-121", tags = listOf("action-cam", "waterproof")),
        // DJI
        KnownProduct("DJI Osmo Action 5 Pro", CAMERAS, "DJI", "DJI Osmo Action 5 Pro -mount -case", tags = listOf("action-cam", "waterproof")),
    )
}
