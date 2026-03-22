package io.github.tieo.arbay.repo

import io.github.tieo.arbay.model.TrackedProduct
import java.util.concurrent.ConcurrentHashMap

class ProductRepo {
    private val products = ConcurrentHashMap<String, TrackedProduct>()

    fun getAll(): List<TrackedProduct> = products.values.toList()

    fun getById(id: String): TrackedProduct? = products[id]

    fun create(product: TrackedProduct): TrackedProduct {
        products[product.id] = product
        return product
    }

    fun update(product: TrackedProduct): TrackedProduct? {
        if (!products.containsKey(product.id)) return null
        products[product.id] = product
        return product
    }

    fun delete(id: String): Boolean = products.remove(id) != null
}
