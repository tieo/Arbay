package io.github.tieo.arbay.catalog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.PlatformId.*

enum class ProductCategory(
    val displayName: String,
    val icon: ImageVector,
    val defaultPlatforms: List<PlatformId>,
) {
    HEADPHONES("Headphones", Icons.Default.Headphones, generalPlatforms),
    SMARTPHONES("Smartphones", Icons.Default.Smartphone, generalPlatforms),
    LAPTOPS("Laptops", Icons.Default.Laptop, generalPlatforms),
    GPUS("GPUs", Icons.Default.Memory, generalPlatforms),
    CONSOLES("Consoles", Icons.Default.SportsEsports, generalPlatforms),
    CAMERAS("Cameras", Icons.Default.CameraAlt, generalPlatforms),
    TABLETS("Tablets", Icons.Default.Tablet, generalPlatforms),
    WATCHES("Watches", Icons.Default.Watch, generalPlatforms),
    AUDIO("Audio", Icons.Default.Speaker, generalPlatforms),
    CARS("Cars", Icons.Default.DirectionsCar, carPlatforms),
}

private val generalPlatforms = listOf(
    EBAY_DE, EBAY_COM, KLEINANZEIGEN, AMAZON_DE, IDEALO, GEIZHALS,
    BACKMARKET_DE, REBUY, REFURBED, VINTED_DE, WILLHABEN, MARKTPLAATS,
)

private val carPlatforms = listOf(
    MOBILE_DE, AUTOSCOUT24, KLEINANZEIGEN, EBAY_DE, WILLHABEN,
)
