package io.github.tieo.arbay.crawler

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * mobile.de's own make and model numbers, from the reference lists its apps read.
 *
 * This site has no text search for a model. Its `q` is "Fahrzeugbeschreibung durchsuchen" — it
 * reads the seller's prose — so a search sent as `q=volkswagen crafter` alongside a set of
 * criteria answered with thirty-four Tiguans: every one of them met the criteria, and none of them
 * was the van asked for. A model is chosen here by number, `ms=<make>;<model>`, and the site then
 * answers with that model only (verified: 2875 offers, all Crafter).
 *
 * The lists come from `m.mobile.de/svc/r/makes/Car` and `/svc/r/models/<makeId>`, which answer a
 * plain client — the search pages themselves do not — and they change about as often as a car
 * maker launches a model, so each is fetched once per process run.
 */
object MobileDeCatalog {

    private val log = LoggerFactory.getLogger(MobileDeCatalog::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private const val MAKES_URL = "https://m.mobile.de/svc/r/makes/Car"
    private fun modelsUrl(makeId: Int) = "https://m.mobile.de/svc/r/models/$makeId"

    @Volatile private var makes: Map<String, Int>? = null
    private val modelsByMake = ConcurrentHashMap<Int, Map<String, Int>>()

    /** The site's number for a make, or null when it does not list one under that name. */
    suspend fun makeId(name: String): Int? {
        val table = makes ?: fetch(MAKES_URL, "makes")?.also { makes = it } ?: return null
        return lookUp(table, name)
    }

    /** The site's number for a model of that make, or null when it lists none under that name. */
    suspend fun modelId(makeId: Int, name: String): Int? {
        val table = modelsByMake[makeId]
            ?: fetch(modelsUrl(makeId), "models")?.also { modelsByMake[makeId] = it }
            ?: return null
        return lookUp(table, name)
    }

    /** `ms` as the site writes it: make, model, and two empty fields for variant and free text. */
    suspend fun modelSelection(make: String, model: String?): String? {
        val makeNumber = makeId(make) ?: return null
        val modelNumber = model?.let { modelId(makeNumber, it) }
        return if (modelNumber == null) "$makeNumber" else "$makeNumber;$modelNumber"
    }

    /** Exact name first, then one that starts the same way: "Caddy" must not win "Caddy Maxi", and
     *  "Golf Variant" should still find "Golf Variant" rather than stopping at "Golf". */
    private fun lookUp(table: Map<String, Int>, name: String): Int? {
        val key = normalize(name)
        if (key.isEmpty()) return null
        table[key]?.let { return it }
        return table.entries
            .filter { it.key.startsWith(key) || key.startsWith(it.key) }
            .minByOrNull { kotlin.math.abs(it.key.length - key.length) }
            ?.value
    }

    private fun normalize(name: String) = name.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** `{"makes":[{"i":25200,"n":"Volkswagen"},…]}` and the same shape for models. */
    private suspend fun fetch(url: String, key: String): Map<String, Int>? = try {
        val body = CurlCffiClient.fetch(url)
        val array = json.parseToJsonElement(body).jsonObject[key]?.jsonArray
        array?.mapNotNull { entry ->
            val obj = entry.jsonObject
            val id = obj["i"]?.jsonPrimitive?.content?.toIntOrNull()
            val name = obj["n"]?.jsonPrimitive?.content
            if (id != null && name != null) normalize(name) to id else null
        }?.toMap()?.takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("mobile.de {} list unavailable: {}", key, e.message?.take(80))
        null
    }
}
