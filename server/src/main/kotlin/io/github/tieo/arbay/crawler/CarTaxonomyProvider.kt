package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarMakeNode
import io.github.tieo.arbay.model.CarModelNode
import io.github.tieo.arbay.model.CarTaxonomy
import io.github.tieo.arbay.model.CarTaxonomySeed
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.*

/**
 * Holds the canonical car taxonomy the app pulls. Starts from the bundled seed; a daily job
 * calls [refresh] to rebuild the make list from AutoScout24's own filter catalog (fetched
 * through the same stealth stack the crawlers use), keeping the seed's curated models.
 *
 * AutoScout24 is pan-European and embeds its full make catalog
 * (props.pageProps.taxonomy.makesSorted, ~290 makes as {label, value}) in the __NEXT_DATA__
 * of any search page, so one fetch yields the whole make list plus the site's numeric make
 * token (used later for precise per-site filtering).
 */
object CarTaxonomyProvider {

    private val log = LoggerFactory.getLogger(CarTaxonomyProvider::class.java)
    private const val CATALOG_URL = "https://www.autoscout24.de/lst?atype=C&cy=D&sort=standard"

    @Volatile
    var current: CarTaxonomy = CarTaxonomySeed.taxonomy
        private set

    /** Live per-make model lists, keyed by make slug. Probed on demand (when the picker opens a
     *  make) and kept for the process lifetime, so a make is fetched from the site at most once —
     *  no bulk crawl of every make, which would be exactly the request volume the crawlers avoid. */
    private val modelCache = java.util.concurrent.ConcurrentHashMap<String, List<CarModelNode>>()

    /** The models for a make: the live-probed catalog if available, else the bundled seed. The
     *  first call for a make fetches AutoScout24's model list for it; later calls hit the cache. */
    suspend fun modelsFor(makeSlug: String): List<CarModelNode> {
        val slug = makeSlug.lowercase()
        modelCache[slug]?.let { return it }
        val seedModels = current.makes.firstOrNull { it.id == slug }?.models
            ?: CarTaxonomySeed.taxonomy.makes.firstOrNull { it.id == slug }?.models
            ?: emptyList()
        val siteId = current.makes.firstOrNull { it.id == slug }?.platformSlugs?.get("AUTOSCOUT24")
        val probed = probeModels(slug, siteId)
        // Only cache and prefer a probed catalog that is at least as complete as the seed, so a
        // thin or failed fetch never shrinks a curated list.
        val models = if (probed.size >= seedModels.size && probed.isNotEmpty()) probed else seedModels
        if (models.isNotEmpty()) modelCache[slug] = models
        return models
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

    /** Rebuild the taxonomy from AutoScout24's live make catalog; falls back to the current
     *  taxonomy on any failure so a bad fetch never empties the picker. */
    suspend fun refresh() {
        try {
            val html = fetchWithFallback(CrawlerRegistry.httpClient, CATALOG_URL, "AutoScout24-taxonomy", waitSelector = "article")
            val makes = parseAutoScout24Makes(html)
            if (makes.size < 30) {
                log.warn("Car taxonomy refresh: only ${makes.size} makes parsed, keeping current")
                return
            }
            current = buildTaxonomy(makes)
            log.info("Car taxonomy refreshed: ${current.makes.size} makes (version ${current.version})")
        } catch (e: Exception) {
            log.warn("Car taxonomy refresh failed: ${e.message}; keeping current")
        }
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

    /** Merge the live make list with the seed's curated models (keyed by canonical slug). */
    private fun buildTaxonomy(makes: List<Pair<String, String>>): CarTaxonomy {
        val seedModels: Map<String, List<CarModelNode>> =
            CarTaxonomySeed.taxonomy.makes.associate { it.id to it.models }

        val nodes = makes
            .distinctBy { slug(it.first) }
            .map { (name, siteId) ->
                val id = slug(name)
                CarMakeNode(
                    id = id,
                    name = name,
                    models = seedModels[id] ?: emptyList(),
                    platformSlugs = mapOf("AUTOSCOUT24" to siteId),
                )
            }
            .sortedBy { it.name.lowercase() }

        // Version is a stable content hash of the make ids, so the app only re-pulls when the
        // catalog actually changes.
        val version = "as24-" + nodes.joinToString(",") { it.id }.hashCode().toUInt().toString(16)
        return CarTaxonomy(version = version, makes = nodes)
    }

    private fun slug(name: String): String =
        name.lowercase()
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
