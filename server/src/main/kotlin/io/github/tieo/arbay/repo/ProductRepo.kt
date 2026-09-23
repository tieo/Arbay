package io.github.tieo.arbay.repo

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.SearchQueryMigration
import io.github.tieo.arbay.model.TrackedProduct
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory

/** Saved searches. Persisted to disk so they survive a server restart / redeploy —
 *  a saved Crafter must not vanish when the container is recreated. */
class ProductRepo {
    private val log = LoggerFactory.getLogger(ProductRepo::class.java)
    private val products = ConcurrentHashMap<String, TrackedProduct>()
    private val persistFile = DataDir.file("tracked_products.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // A load that threw left this empty while the file on disk still holds every saved search.
    // Persisting from that state would write the emptiness over them, so nothing may be written
    // until someone has looked at why the file could not be read.
    private var loadFailed = false

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
            loadFailed = true
            log.error(
                "Could not read {} — saved searches are not editable until this is fixed, so the " +
                    "file is not overwritten: {}",
                persistFile, e.message,
            )
        }
    }

    @Synchronized
    private fun persist() {
        if (loadFailed) {
            log.error("Refusing to write saved searches over a file that could not be read")
            return
        }
        try {
            persistFile.writeTextAtomically(json.encodeToString(products.values.toList()))
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
