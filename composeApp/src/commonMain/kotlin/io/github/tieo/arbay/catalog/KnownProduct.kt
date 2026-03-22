package io.github.tieo.arbay.catalog

import io.github.tieo.arbay.model.PlatformId

data class KnownProduct(
    val displayName: String,
    val category: ProductCategory,
    val brand: String,
    val searchQuery: String,
    val mpn: String? = null,
    val gtins: List<String> = emptyList(),
    val platforms: List<PlatformId>? = null,
    val tags: List<String> = emptyList(),
) {
    val effectivePlatforms: List<PlatformId>
        get() = platforms ?: category.defaultPlatforms
}
