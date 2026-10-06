package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.CAMERAS

object CamerasCatalog {
    val products = listOf(
        // Sony
        KnownProduct("Sony A7 IV", CAMERAS, "Sony", "Sony A7 IV", "ILCE-7M4", listOf("4548736128712"), tags = listOf("mirrorless", "full-frame"), aliases = listOf("A7IV", "ILCE-7M4"), excludeKeywords = listOf("lens", "cage", "rig")),
        KnownProduct("Sony A7C II", CAMERAS, "Sony", "Sony A7C II", "ILCE-7CM2", tags = listOf("mirrorless", "full-frame", "compact"), aliases = listOf("A7CII", "ILCE-7CM2"), excludeKeywords = listOf("lens", "cage")),
        KnownProduct("Sony A6700", CAMERAS, "Sony", "Sony A6700", "ILCE-6700", listOf("4548736150225"), tags = listOf("mirrorless", "aps-c"), aliases = listOf("ILCE-6700"), excludeKeywords = listOf("lens", "cage", "rig")),
        KnownProduct("Sony ZV-E10 II", CAMERAS, "Sony", "Sony ZV-E10 II", "ZV-E10M2", tags = listOf("mirrorless", "aps-c", "vlog"), aliases = listOf("ZV-E10M2"), excludeKeywords = listOf("lens", "cage")),
        // Canon
        KnownProduct("Canon EOS R6 Mark II", CAMERAS, "Canon", "Canon R6 Mark II", "EOS R6 II", listOf("4549292200348"), tags = listOf("mirrorless", "full-frame"), aliases = listOf("R6II", "EOS R6 II"), excludeKeywords = listOf("lens", "cage")),
        KnownProduct("Canon EOS R8", CAMERAS, "Canon", "Canon EOS R8", "EOS R8", listOf("4549292207743"), tags = listOf("mirrorless", "full-frame", "compact"), excludeKeywords = listOf("lens", "cage", "grip")),
        KnownProduct("Canon EOS R50", CAMERAS, "Canon", "Canon EOS R50", "EOS R50", listOf("4549292207071"), tags = listOf("mirrorless", "aps-c", "entry"), excludeKeywords = listOf("lens", "cage")),
        // Nikon
        KnownProduct("Nikon Z8", CAMERAS, "Nikon", "Nikon Z8", "Z8", listOf("4960759910295"), tags = listOf("mirrorless", "full-frame", "flagship"), excludeKeywords = listOf("lens", "cage", "grip", "rig")),
        KnownProduct("Nikon Z6 III", CAMERAS, "Nikon", "Nikon Z6 III", "Z6 III", tags = listOf("mirrorless", "full-frame"), aliases = listOf("Z6III"), excludeKeywords = listOf("lens", "cage")),
        // Fujifilm
        KnownProduct("Fujifilm X-T5", CAMERAS, "Fujifilm", "Fujifilm X-T5", "X-T5", listOf("4547410467376"), tags = listOf("mirrorless", "aps-c", "retro"), aliases = listOf("XT5"), excludeKeywords = listOf("lens", "cage", "grip")),
        KnownProduct("Fujifilm X-S20", CAMERAS, "Fujifilm", "Fujifilm X-S20", "X-S20", listOf("4547410473964"), tags = listOf("mirrorless", "aps-c", "vlog"), aliases = listOf("XS20"), excludeKeywords = listOf("lens", "cage")),
        KnownProduct("Fujifilm X100VI", CAMERAS, "Fujifilm", "Fujifilm X100VI", "X100VI", tags = listOf("compact", "aps-c", "fixed-lens", "retro"), aliases = listOf("X100 VI"), excludeKeywords = listOf("case", "filter")),
        // GoPro
        KnownProduct("GoPro HERO13 Black", CAMERAS, "GoPro", "GoPro HERO13", "CHDHX-131", tags = listOf("action-cam", "waterproof"), aliases = listOf("Hero 13"), excludeKeywords = listOf("mount", "case", "accessory")),
        KnownProduct("GoPro HERO12 Black", CAMERAS, "GoPro", "GoPro HERO12", "CHDHX-121", tags = listOf("action-cam", "waterproof"), aliases = listOf("Hero 12"), excludeKeywords = listOf("mount", "case", "accessory")),
        // DJI
        KnownProduct("DJI Osmo Action 5 Pro", CAMERAS, "DJI", "DJI Osmo Action 5 Pro", tags = listOf("action-cam", "waterproof"), excludeKeywords = listOf("mount", "case")),
    )
}
