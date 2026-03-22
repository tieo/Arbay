package io.github.tieo.arbay.catalog.data

import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory.GPUS

object GpusCatalog {
    val products = listOf(
        // NVIDIA
        KnownProduct("NVIDIA RTX 5090", GPUS, "NVIDIA", "RTX 5090 -bracket -cable -waterblock", tags = listOf("nvidia", "flagship", "ada")),
        KnownProduct("NVIDIA RTX 5080", GPUS, "NVIDIA", "RTX 5080 -bracket -cable -waterblock", tags = listOf("nvidia", "high-end", "ada")),
        KnownProduct("NVIDIA RTX 5070 Ti", GPUS, "NVIDIA", "RTX 5070 Ti -bracket -cable", tags = listOf("nvidia", "midrange")),
        KnownProduct("NVIDIA RTX 5070", GPUS, "NVIDIA", "RTX 5070 -Ti -bracket -cable", tags = listOf("nvidia", "midrange")),
        KnownProduct("NVIDIA RTX 4090", GPUS, "NVIDIA", "RTX 4090 -bracket -cable -waterblock", tags = listOf("nvidia", "flagship", "ada")),
        KnownProduct("NVIDIA RTX 4080 Super", GPUS, "NVIDIA", "RTX 4080 Super -bracket -cable", tags = listOf("nvidia", "high-end", "ada")),
        KnownProduct("NVIDIA RTX 4070 Ti Super", GPUS, "NVIDIA", "RTX 4070 Ti Super -bracket -cable", tags = listOf("nvidia", "midrange", "ada")),
        KnownProduct("NVIDIA RTX 4070 Super", GPUS, "NVIDIA", "RTX 4070 Super -Ti -bracket -cable", tags = listOf("nvidia", "midrange", "ada")),
        KnownProduct("NVIDIA RTX 4070", GPUS, "NVIDIA", "RTX 4070 -Ti -Super -bracket -cable", tags = listOf("nvidia", "midrange", "ada")),
        KnownProduct("NVIDIA RTX 4060 Ti", GPUS, "NVIDIA", "RTX 4060 Ti -bracket -cable", tags = listOf("nvidia", "budget", "ada")),
        KnownProduct("NVIDIA RTX 4060", GPUS, "NVIDIA", "RTX 4060 -Ti -bracket -cable", tags = listOf("nvidia", "budget", "ada")),
        // AMD
        KnownProduct("AMD RX 9070 XT", GPUS, "AMD", "RX 9070 XT -bracket -cable", tags = listOf("amd", "rdna4", "high-end")),
        KnownProduct("AMD RX 7900 XTX", GPUS, "AMD", "RX 7900 XTX -bracket -cable -waterblock", tags = listOf("amd", "rdna3", "flagship")),
        KnownProduct("AMD RX 7900 XT", GPUS, "AMD", "RX 7900 XT -XTX -bracket -cable", tags = listOf("amd", "rdna3", "high-end")),
        KnownProduct("AMD RX 7800 XT", GPUS, "AMD", "RX 7800 XT -bracket -cable", tags = listOf("amd", "rdna3", "midrange")),
        KnownProduct("AMD RX 7700 XT", GPUS, "AMD", "RX 7700 XT -bracket -cable", tags = listOf("amd", "rdna3", "midrange")),
    )
}
