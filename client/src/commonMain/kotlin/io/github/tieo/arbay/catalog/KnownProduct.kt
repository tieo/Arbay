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
    // Alternate spellings/model codes a listing might use instead of [searchQuery] (e.g. "XT5" for
    // "Fujifilm X-T5"). Scored as alternatives, kept as their own field rather than folded into
    // searchQuery so the search box only ever shows the one phrase a person would type.
    val aliases: List<String> = emptyList(),
    // Words that mark a listing as an accessory/part for this product rather than the product
    // itself ("lens", "cage" for a camera body). Same reasoning as [aliases].
    val excludeKeywords: List<String> = emptyList(),
) {
    val effectivePlatforms: List<PlatformId>
        get() = platforms ?: category.defaultPlatforms
}
