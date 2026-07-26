package io.github.tieo.arbay.crawler

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * How many different searches a marketplace has offered each related term under.
 *
 * A term the market suggests for one product is a name for that product. A term it suggests for
 * many unrelated ones is something else: a make every seller writes (einhell under five different
 * tools, stihl under three), or a category so broad it sits beside everything (rasenmäher offered
 * under heckenschere, motorsense, vertikutierer and rasentraktor alike). Measured over 32 niche
 * products, every term seen under three or more searches was a make or a neighbouring category,
 * while the real synonyms — betonmischer, motorsäge, freischneider — were each seen under one or
 * two.
 *
 * Kept on disk because the signal is only as good as the number of searches behind it, and those
 * accumulate across restarts.
 */
object SuggestionStats {

    private val log = LoggerFactory.getLogger(SuggestionStats::class.java)
    private val file = File(System.getProperty("user.home"), ".arbay/suggestion_stats.json")

    /** term → the distinct query texts it has been suggested under. */
    private val seenUnder = ConcurrentHashMap<String, MutableSet<String>>()

    @Serializable
    private data class Persisted(val seenUnder: Map<String, List<String>> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }

    init {
        runCatching {
            if (file.exists()) {
                json.decodeFromString(Persisted.serializer(), file.readText()).seenUnder
                    .forEach { (term, queries) -> seenUnder[term] = queries.toMutableSet() }
                log.info("Suggestion stats loaded for {} terms", seenUnder.size)
            }
        }.onFailure { log.warn("Suggestion stats unreadable: {}", it.message) }
    }

    /** Record that [query]'s results page offered these related searches. */
    fun record(query: String, terms: List<String>) {
        if (terms.isEmpty()) return
        val q = query.trim().lowercase()
        for (term in terms) {
            seenUnder.computeIfAbsent(term.trim().lowercase()) { ConcurrentHashMap.newKeySet() }.add(q)
        }
        persist()
    }

    /** How many distinct searches this term has been offered under. */
    fun searchesOfferingIt(term: String): Int = seenUnder[term.trim().lowercase()]?.size ?: 0

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(
                Persisted.serializer(),
                Persisted(seenUnder.mapValues { it.value.toList() }),
            ))
        }.onFailure { log.debug("Suggestion stats not written: {}", it.message) }
    }
}
