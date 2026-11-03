package io.github.tieo.arbay.relevance

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.CarMakeNode
import io.github.tieo.arbay.model.CarModelNode
import io.github.tieo.arbay.model.CarTaxonomy
import io.github.tieo.arbay.model.CarTaxonomySeed
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import io.ktor.http.encodeURLParameter
import org.slf4j.LoggerFactory

/**
 * Holds the canonical car taxonomy the app pulls: every make AND its full model list, live from
 * AutoScout24's own filter catalog. A daily background job (see Application.kt) is the only thing
 * that fetches this — the app just reads whatever [current] holds, on its own sync-on-start
 * schedule ([io.github.tieo.arbay.routes.taxonomyRoutes]). No client action ever triggers a fetch.
 * [current] is also mirrored to disk after every successful refresh and reloaded from there at
 * startup, so a restart (a deploy, in particular) serves the full catalog immediately rather than
 * the thin bundled seed for the several minutes the next refresh takes to finish.
 *
 * AutoScout24 is pan-European, and Neuland reads its filter catalog: about 290 makes, and each
 * make's full model list, so this is the real, unabridged catalog the site itself offers, not a
 * hand-typed guess at "the popular models" (which is what shipped before, and was missing things
 * like the Ford S-Max).
 */
object CarTaxonomyProvider {

    private val log = LoggerFactory.getLogger(CarTaxonomyProvider::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val persistFile = DataDir.file("car_taxonomy.json")

    // Loaded from yesterday's successful refresh when present, so a restart serves the full
    // catalog immediately instead of the thin bundled seed for the ~10 minutes a fresh refresh
    // takes — a deploy is exactly when this would otherwise bite hardest.
    @Volatile
    var current: CarTaxonomy = loadPersisted() ?: CarTaxonomySeed.taxonomy
        private set

    /** Whether [current] is still the bundled seed rather than the live catalogue. */
    val isSeed: Boolean get() = current === CarTaxonomySeed.taxonomy

    private fun loadPersisted(): CarTaxonomy? =
        persistFile.readStore(log) { json.decodeFromString<CarTaxonomy>(it) }

    private fun persist(taxonomy: CarTaxonomy) = synchronized(persistFile) {
        try {
            persistFile.writeTextAtomically(json.encodeToString(taxonomy))
        } catch (e: Exception) {
            log.error("Could not persist car taxonomy: {}", e.message)
        }
    }

    @kotlinx.serialization.Serializable private data class Entry(val name: String, val id: String)

    /** AutoScout24's makes, by Neuland. */
    private suspend fun makes(): List<Entry> =
        json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Entry.serializer()),
            io.github.tieo.arbay.neuland.Neuland.request(io.ktor.http.HttpMethod.Get, "vehicles/makes?site=autoscout24.de", limitS = 150))

    /** One make's models on AutoScout24, by Neuland, each carrying the site's model token. */
    private suspend fun probeModels(make: Entry): List<CarModelNode> = try {
        val path = "vehicles/models?site=autoscout24.de&make=${make.name.encodeURLParameter()}&id=${make.id.encodeURLParameter()}"
        json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Entry.serializer()), io.github.tieo.arbay.neuland.Neuland.request(io.ktor.http.HttpMethod.Get, path, limitS = 150))
            .map { CarModelNode(id = slug(it.name), name = it.name, platformSlugs = mapOf("AUTOSCOUT24" to it.id)) }
            .distinctBy { it.id }.sortedBy { it.name.lowercase() }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        log.warn("Model probe for {} failed: {}", make.name, e.message)
        emptyList()
    }

    /** Rebuild the whole taxonomy — make list AND every make's live model list — from AutoScout24.
     *  Runs once at boot and once a day (Application.kt); nothing else ever calls this, so the
     *  fetch volume is one make-list request plus one model request per make, paced, on a fixed
     *  schedule, not driven by how the app is used. Falls back to the current taxonomy on any
     *  failure so a bad fetch never empties the picker. */
    suspend fun refresh() {
        val makes = try {
            makes()
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.warn("Car taxonomy refresh failed: ${e.message}; keeping current")
            return
        }
        if (makes.size < 30) {
            log.warn("Car taxonomy refresh: only ${makes.size} makes parsed, keeping current")
            return
        }

        val seedModels: Map<String, List<CarModelNode>> =
            CarTaxonomySeed.taxonomy.makes.associate { it.id to it.models }
        val previousModels: Map<String, List<CarModelNode>> =
            current.makes.associate { it.id to it.models }

        val nodes = makes.distinctBy { slug(it.name) }.mapIndexed { index, make ->
            val id = slug(make.name)
            // Paced so a full pass over ~290 makes stays a slow trickle, the same reasoning the
            // interactive crawlers already throttle by, not a burst against one site's IP.
            if (index > 0) delay(800L + Random.nextLong(200, 700))
            val probed = try {
                probeModels(make)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.debug("Model probe for {} failed: {}", id, e.message)
                emptyList()
            }
            // A failed or empty probe keeps yesterday's live-probed list before falling back to
            // the bundled seed, so one bad night never regresses a make that already synced fine.
            val models = probed.ifEmpty { previousModels[id]?.ifEmpty { null } ?: seedModels[id] ?: emptyList() }
            if ((index + 1) % 25 == 0) log.info("Car taxonomy models: {}/{} makes probed", index + 1, makes.size)
            CarMakeNode(id = id, name = make.name, models = models, platformSlugs = mapOf("AUTOSCOUT24" to make.id))
        }.sortedBy { it.name.lowercase() }

        // Version hashes both the make list AND every make's model ids, so the app re-pulls
        // whenever a model catalog changes too, not only when a make is added or removed.
        val version = "as24-" + nodes.joinToString("|") {
            it.id + ":" + it.models.joinToString(",") { m -> m.id }
        }.hashCode().toUInt().toString(16)
        current = CarTaxonomy(version = version, makes = nodes)
        persist(current)
        log.info(
            "Car taxonomy refreshed: {} makes, {} total models (version {})",
            current.makes.size, current.makes.sumOf { it.models.size }, current.version,
        )
    }

    private fun slug(name: String): String =
        name.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
