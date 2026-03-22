package io.github.tieo.arbay.catalog

import io.github.tieo.arbay.catalog.data.*

object ProductCatalog {

    val products: List<KnownProduct> by lazy {
        HeadphonesCatalog.products +
            SmartphonesCatalog.products +
            LaptopsCatalog.products +
            GpusCatalog.products +
            ConsoleCatalog.products +
            CamerasCatalog.products +
            TabletsCatalog.products +
            WatchesCatalog.products +
            AudioCatalog.products +
            CarsCatalog.products
    }

    fun brandsFor(category: ProductCategory): List<String> =
        products.filter { it.category == category }
            .map { it.brand }
            .distinct()
            .sorted()

    fun productsFor(category: ProductCategory, brand: String? = null): List<KnownProduct> =
        products.filter {
            it.category == category && (brand == null || it.brand == brand)
        }

    fun search(query: String): List<KnownProduct> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        return products.filter {
            it.displayName.contains(q, ignoreCase = true) ||
                it.brand.contains(q, ignoreCase = true) ||
                it.mpn?.contains(q, ignoreCase = true) == true ||
                it.searchQuery.contains(q, ignoreCase = true) ||
                it.tags.any { t -> t.contains(q, ignoreCase = true) }
        }
    }
}
