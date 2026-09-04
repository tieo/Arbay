package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarMakeNode
import io.github.tieo.arbay.model.CarModelNode
import io.github.tieo.arbay.model.CarTaxonomy
import io.github.tieo.arbay.model.CarTaxonomySeed
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.*
import java.io.File
import kotlin.random.Random

/**
 * Holds the canonical car taxonomy the app pulls: every make AND its full model list, live from
 * AutoScout24's own filter catalog. A daily background job (see Application.kt) is the only thing
 * that fetches this — the app just reads whatever [current] holds, on its own sync-on-start
 * schedule ([io.github.tieo.arbay.routes.taxonomyRoutes]). No client action ever triggers a fetch.
 * [current] is also mirrored to disk after every successful refresh and reloaded from there at
 * startup, so a restart (a deploy, in particular) serves the full catalog immediately rather than
 * the thin bundled seed for the several minutes the next refresh takes to finish.
 *
 * AutoScout24 is pan-European and embeds its full make catalog
 * (props.pageProps.taxonomy.makesSorted, ~290 makes as {label, value}) in the __NEXT_DATA__ of
 * any search page, and each make's own listing page embeds that make's full model list the same
 * way — so this is the real, unabridged filter catalog the site itself offers, not a hand-typed
 * guess at "the popular models" (which is what shipped before, and was missing things like the
 * Ford S-Max).
 */
object CarTaxonomyProvider {

    private val log = LoggerFactory.getLogger(CarTaxonomyProvider::class.java)
    private const val CATALOG_URL = "https://www.autoscout24.de/lst?atype=C&cy=D&sort=standard"
    private val json = Json { ignoreUnknownKeys = true }
    private val persistFile = File(System.getProperty("user.home"), ".arbay/car_taxonomy.json")

    // Loaded from yesterday's successful refresh when present, so a restart serves the full
    // catalog immediately instead of the thin bundled seed for the ~10 minutes a fresh refresh
    // takes — a deploy is exactly when this would otherwise bite hardest.
    @Volatile
    var current: CarTaxonomy = loadPersisted() ?: CarTaxonomySeed.taxonomy
        private set

    private fun loadPersisted(): CarTaxonomy? = try {
        if (persistFile.exists()) json.decodeFromString<CarTaxonomy>(persistFile.readText()) else null
    } catch (e: Exception) {
        log.warn("Could not load persisted car taxonomy: {}", e.message)
        null
    }

    private fun persist(taxonomy: CarTaxonomy) {
        try {
            persistFile.parentFile.mkdirs()
            persistFile.writeText(json.encodeToString(taxonomy))
        } catch (e: Exception) {
            log.warn("Could not persist car taxonomy: {}", e.message)
        }
    }

    /** Fetch AutoScout24's model catalog for one make. The make page embeds every make's models
     *  under taxonomy.models keyed by numeric make id; the selected make's list is the one to read. */
    private suspend fun probeModels(makeSlug: String, siteId: String?): List<CarModelNode> = try {
        val url = "https://www.autoscout24.de/lst/$makeSlug?atype=C&cy=D&sort=standard"
        val html = fetchWithFallback(CrawlerRegistry.httpClient, url, "AutoScout24-models", waitSelector = "article")
        parseAutoScout24Models(html, siteId)
    } catch (e: Exception) {
        log.warn("Model probe for {} failed: {}", makeSlug, e.message)
        emptyList()
    }

    /** taxonomy.models is a map of make id -> [{value, label}]; pick the target make (or the sole
     *  entry) and turn each into a CarModelNode carrying the AutoScout24 model token. */
    internal fun parseAutoScout24Models(html: String, siteId: String?): List<CarModelNode> {
        val nextData = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(nextData).jsonObject
        val models = root["props"]?.jsonObject?.get("pageProps")?.jsonObject
            ?.get("taxonomy")?.jsonObject?.get("models")?.jsonObject ?: return emptyList()
        val entry = (siteId?.let { models[it] } ?: models.values.singleOrNull())?.jsonArray ?: return emptyList()
        return entry.mapNotNull { el ->
            val o = el.jsonObject
            val label = o["label"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = o["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            CarModelNode(id = slug(label), name = label, platformSlugs = mapOf("AUTOSCOUT24" to value))
        }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    /** Rebuild the whole taxonomy — make list AND every make's live model list — from AutoScout24.
     *  Runs once at boot and once a day (Application.kt); nothing else ever calls this, so the
     *  fetch volume is one make-list request plus one model request per make, paced, on a fixed
     *  schedule, not driven by how the app is used. Falls back to the current taxonomy on any
     *  failure so a bad fetch never empties the picker. */
    suspend fun refresh() {
        val makes = try {
            val html = fetchWithFallback(CrawlerRegistry.httpClient, CATALOG_URL, "AutoScout24-taxonomy", waitSelector = "article")
            parseAutoScout24Makes(html)
        } catch (e: Exception) {
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

        val nodes = makes.distinctBy { slug(it.first) }.mapIndexed { index, (name, siteId) ->
            val id = slug(name)
            // Paced so a full pass over ~290 makes stays a slow trickle, the same reasoning the
            // interactive crawlers already throttle by, not a burst against one site's IP.
            if (index > 0) delay(800L + Random.nextLong(200, 700))
            val probed = try {
                probeModels(id, siteId)
            } catch (e: Exception) {
                log.debug("Model probe for {} failed: {}", id, e.message)
                emptyList()
            }
            // A failed or empty probe keeps yesterday's live-probed list before falling back to
            // the bundled seed, so one bad night never regresses a make that already synced fine.
            val models = probed.ifEmpty { previousModels[id]?.ifEmpty { null } ?: seedModels[id] ?: emptyList() }
            if ((index + 1) % 25 == 0) log.info("Car taxonomy models: {}/{} makes probed", index + 1, makes.size)
            CarMakeNode(id = id, name = name, models = models, platformSlugs = mapOf("AUTOSCOUT24" to siteId))
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

    /** Parse AutoScout24 __NEXT_DATA__ props.pageProps.taxonomy.makesSorted -> (name, siteId). */
    internal fun parseAutoScout24Makes(html: String): List<Pair<String, String>> {
        val nextData = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val root = Json { ignoreUnknownKeys = true }.parseToJsonElement(nextData).jsonObject
        val makesSorted = root["props"]?.jsonObject
            ?.get("pageProps")?.jsonObject
            ?.get("taxonomy")?.jsonObject
            ?.get("makesSorted")?.jsonArray ?: return emptyList()
        return makesSorted.mapNotNull { el ->
            val o = el.jsonObject
            val label = o["label"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
            val value = o["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (label.isBlank()) null else label to value
        }
    }

    private fun slug(name: String): String =
        name.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
