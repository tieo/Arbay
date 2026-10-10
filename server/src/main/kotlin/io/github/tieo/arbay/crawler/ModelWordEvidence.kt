package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * What the markets' answers have shown a model word to go with, across every search the server ran.
 *
 * A market whose answer is small has too few listings to tell from one another that "990" is an
 * NVMe drive: Ricardo answered "4TB M.2" with 990 PROs that did not write "M.2", and lost every one. eBay and Kleinanzeigen, asked the same, answer with dozens that do. Kept per pair of
 * model word and searched word, counted once per listing, and on disk, because the evidence is only
 * as good as the number of answers behind it and those accumulate across restarts.
 *
 * Switched on by the server at start ([install]). Off, as in tests, every search is judged on its
 * own answer alone, so no test depends on what another one searched.
 */
class ModelWordEvidence(private val file: File?) {

    @Serializable
    private data class Persisted(
        /** "model|word" → listing id → whether that listing carried the word. */
        val pairs: Map<String, Map<String, Boolean>> = emptyMap(),
    )

    private val pairs = HashMap<String, LinkedHashMap<String, Boolean>>()
    private var lastWrite = 0L

    init {
        file?.readStore(log) { json.decodeFromString(Persisted.serializer(), it) }?.let { p ->
            p.pairs.forEach { (k, v) -> pairs[k] = LinkedHashMap(v) }
            log.info("Model word evidence loaded for {} pairs", pairs.size)
        }
    }

    /** Whether listings with [model] have, across the answers seen, carried [word]. */
    @Synchronized
    fun shows(model: String, word: String): Boolean {
        val seen = pairs["$model|$word"] ?: return false
        if (seen.size < MIN_LISTINGS) return false
        return seen.values.count { it }.toDouble() / seen.size >= MIN_CARRYING_SHARE
    }

    /** One market's answer: each listing's model words and which searched words it carried. */
    @Synchronized
    fun record(listingModels: Map<String, Set<String>>, carried: Map<String, Set<String>>, words: List<String>) {
        for ((id, models) in listingModels) for (m in models) for (w in words) {
            val seen = pairs.getOrPut("$m|$w") { LinkedHashMap() }
            seen[id] = w in carried[id].orEmpty()
            // The most recent listings stand for the pair; older ones make way.
            while (seen.size > MAX_LISTINGS_PER_PAIR) seen.remove(seen.keys.first())
        }
        persistNowAndThen()
    }

    private fun persistNowAndThen() {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (now - lastWrite < WRITE_EVERY_MS) return
        lastWrite = now
        runCatching {
            f.writeTextAtomically(json.encodeToString(
                Persisted.serializer(),
                Persisted(pairs.mapValues { it.value.toMap() }),
            ))
        }.onFailure { log.error("Model word evidence not written: {}", it.message) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ModelWordEvidence::class.java)
        private val json = Json { ignoreUnknownKeys = true }

        /** Listings a pair needs before it says anything. */
        private const val MIN_LISTINGS = 5

        /** The share of them that have to carry the word. */
        private const val MIN_CARRYING_SHARE = 0.6

        private const val MAX_LISTINGS_PER_PAIR = 300

        /** Written at most this often; a restart loses no more than the answers since. */
        private const val WRITE_EVERY_MS = 30_000L

        @Volatile
        var installed: ModelWordEvidence? = null
            internal set

        fun install(file: File) {
            installed = ModelWordEvidence(file)
        }
    }
}
