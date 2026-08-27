package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.GPUS

object GpusCatalog {
    val products = listOf(
        // NVIDIA
        KnownProduct("NVIDIA RTX 5090", GPUS, "NVIDIA", "RTX 5090", tags = listOf("nvidia", "flagship", "ada"), excludeKeywords = listOf("bracket", "cable", "waterblock")),
        KnownProduct("NVIDIA RTX 5080", GPUS, "NVIDIA", "RTX 5080", tags = listOf("nvidia", "high-end", "ada"), excludeKeywords = listOf("bracket", "cable", "waterblock")),
        KnownProduct("NVIDIA RTX 5070 Ti", GPUS, "NVIDIA", "RTX 5070 Ti", tags = listOf("nvidia", "midrange"), excludeKeywords = listOf("bracket", "cable")),
        KnownProduct("NVIDIA RTX 5070", GPUS, "NVIDIA", "RTX 5070", tags = listOf("nvidia", "midrange"), excludeKeywords = listOf("Ti", "bracket", "cable")),
        KnownProduct("NVIDIA RTX 4090", GPUS, "NVIDIA", "RTX 4090", tags = listOf("nvidia", "flagship", "ada"), excludeKeywords = listOf("bracket", "cable", "waterblock")),
        KnownProduct("NVIDIA RTX 4080 Super", GPUS, "NVIDIA", "RTX 4080 Super", tags = listOf("nvidia", "high-end", "ada"), excludeKeywords = listOf("bracket", "cable")),
        KnownProduct("NVIDIA RTX 4070 Ti Super", GPUS, "NVIDIA", "RTX 4070 Ti Super", tags = listOf("nvidia", "midrange", "ada"), excludeKeywords = listOf("bracket", "cable")),
        KnownProduct("NVIDIA RTX 4070 Super", GPUS, "NVIDIA", "RTX 4070 Super", tags = listOf("nvidia", "midrange", "ada"), excludeKeywords = listOf("Ti", "bracket", "cable")),
        KnownProduct("NVIDIA RTX 4070", GPUS, "NVIDIA", "RTX 4070", tags = listOf("nvidia", "midrange", "ada"), excludeKeywords = listOf("Ti", "Super", "bracket", "cable")),
        KnownProduct("NVIDIA RTX 4060 Ti", GPUS, "NVIDIA", "RTX 4060 Ti", tags = listOf("nvidia", "budget", "ada"), excludeKeywords = listOf("bracket", "cable")),
        KnownProduct("NVIDIA RTX 4060", GPUS, "NVIDIA", "RTX 4060", tags = listOf("nvidia", "budget", "ada"), excludeKeywords = listOf("Ti", "bracket", "cable")),
        // AMD
        KnownProduct("AMD RX 9070 XT", GPUS, "AMD", "RX 9070 XT", tags = listOf("amd", "rdna4", "high-end"), excludeKeywords = listOf("bracket", "cable")),
        KnownProduct("AMD RX 7900 XTX", GPUS, "AMD", "RX 7900 XTX", tags = listOf("amd", "rdna3", "flagship"), excludeKeywords = listOf("bracket", "cable", "waterblock")),
        KnownProduct("AMD RX 7900 XT", GPUS, "AMD", "RX 7900 XT", tags = listOf("amd", "rdna3", "high-end"), excludeKeywords = listOf("XTX", "bracket", "cable")),
        KnownProduct("AMD RX 7800 XT", GPUS, "AMD", "RX 7800 XT", tags = listOf("amd", "rdna3", "midrange"), excludeKeywords = listOf("bracket", "cable")),
        KnownProduct("AMD RX 7700 XT", GPUS, "AMD", "RX 7700 XT", tags = listOf("amd", "rdna3", "midrange"), excludeKeywords = listOf("bracket", "cable")),
    )
}
