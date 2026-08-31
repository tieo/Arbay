package io.github.tieo.arbay.repo

import io.github.tieo.arbay.model.SearchQueryMigration
import io.github.tieo.arbay.model.TrackedProduct
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Saved searches. Persisted to disk so they survive a server restart / redeploy —
 *  a saved Crafter must not vanish when the container is recreated. */
class ProductRepo {
    private val log = LoggerFactory.getLogger(ProductRepo::class.java)
    private val products = ConcurrentHashMap<String, TrackedProduct>()
    private val persistFile = File(System.getProperty("user.home"), ".arbay/tracked_products.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init { load() }

    private fun load() {
        try {
            if (!persistFile.exists()) return
            val raw = json.parseToJsonElement(persistFile.readText()) as JsonArray
            val migrated = SearchQueryMigration.migrateList(raw)
            json.decodeFromJsonElement<List<TrackedProduct>>(migrated)
                .forEach { products[it.id] = it }
            log.info("Loaded ${products.size} saved searches")
            // Old records missing searchQuery.category (or carrying platforms outside it) were
            // just backfilled in memory — persist that once so the file self-heals instead of
            // re-migrating from the same stale JSON on every restart.
            if (migrated != raw) persist()
        } catch (e: Exception) {
            log.warn("Failed to load saved searches: ${e.message}")
        }
    }

    @Synchronized
    private fun persist() {
        try {
            persistFile.parentFile.mkdirs()
            persistFile.writeText(json.encodeToString(products.values.toList()))
        } catch (e: Exception) {
            log.warn("Failed to persist saved searches: ${e.message}")
        }
    }

    fun getAll(): List<TrackedProduct> = products.values.toList()

    fun getById(id: String): TrackedProduct? = products[id]

    fun create(product: TrackedProduct): TrackedProduct {
        products[product.id] = product
        persist()
        return product
    }

    fun update(product: TrackedProduct): TrackedProduct? {
        if (!products.containsKey(product.id)) return null
        products[product.id] = product
        persist()
        return product
    }

    fun delete(id: String): Boolean {
        val removed = products.remove(id) != null
        if (removed) persist()
        return removed
    }
}
