package io.github.tieo.arbay.catalog

import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.MarketSets

// One list per kind of search, shared with the forms that start one, so a search from a tile
// covers exactly what the same search typed covers.
private val generalPlatforms = MarketSets.general
private val carPlatforms = MarketSets.vehicles

enum class ProductCategory(
    val displayName: String,
    val defaultPlatforms: List<PlatformId>,
) {
    HEADPHONES("Headphones", generalPlatforms),
    SMARTPHONES("Smartphones", generalPlatforms),
    LAPTOPS("Laptops", generalPlatforms),
    GPUS("GPUs", generalPlatforms),
    CONSOLES("Consoles", generalPlatforms),
    CAMERAS("Cameras", generalPlatforms),
    TABLETS("Tablets", generalPlatforms),
    WATCHES("Watches", generalPlatforms),
    AUDIO("Audio", generalPlatforms),
    CARS("Cars", carPlatforms),
}


