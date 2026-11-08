package io.github.tieo.arbay.judging

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.FitAlertDecision
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * Which offers a fit alert has told of, so each is pushed once, and every decision it took on one,
 * kept in the data folder since the server's own log does not reach back far enough to ask why an
 * offer was not pushed.
 */
object FitAlertLog {
    private val log = LoggerFactory.getLogger(FitAlertLog::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val toldFile = DataDir.file("fit_alert_told.json")
    private val decisionsFile = DataDir.file("fit_alert_decisions.jsonl")
    private const val MOST_BYTES = 5_000_000L

    private val told: MutableMap<String, MutableSet<String>> =
        toldFile.readStore(log) { json.decodeFromString<Map<String, Set<String>>>(it) }?.mapValuesTo(HashMap()) { it.value.toMutableSet() } ?: HashMap()

    /** The offers told of, or null where this search's alert has not run since they were kept. */
    fun told(productId: String): Set<String>? = synchronized(told) { told[productId]?.toSet() }

    fun note(productId: String, offers: Collection<String>) {
        synchronized(told) {
            told.getOrPut(productId) { HashSet() }.addAll(offers)
            runCatching { toldFile.writeTextAtomically(json.encodeToString(told.mapValues { it.value.toSet() })) }
                .onFailure { log.error("Could not save which offers a fit alert told of: {}", it.message) }
        }
    }

    /** The last decision written per search and offer: a decision that stands is written once. */
    private val last = HashMap<String, String>()

    fun decisions(product: TrackedProduct, floor: Double, decided: List<Triple<Listing, Double, String>>) {
        synchronized(decisionsFile) { write(product, floor, decided) }
    }

    private fun write(product: TrackedProduct, floor: Double, decided: List<Triple<Listing, Double, String>>) {
        val now = Clock.System.now()
        val lines = decided.mapNotNull { (l, score, decision) ->
            if (last.put("${product.id} ${l.id}", decision) == decision) return@mapNotNull null
            json.encodeToString(FitAlertDecision.serializer(), FitAlertDecision(now, product.id, product.name, l.id, l.title, l.url, score, floor, decision))
        }
        if (lines.isEmpty()) return
        runCatching {
            if (decisionsFile.length() > MOST_BYTES) decisionsFile.renameTo(DataDir.file("fit_alert_decisions.old.jsonl"))
            decisionsFile.appendText(lines.joinToString("\n", postfix = "\n"))
        }.onFailure { log.error("Could not save a fit alert's decisions: {}", it.message) }
    }

    /** The latest decisions of a search, newest first. */
    fun read(productId: String, most: Int): List<FitAlertDecision> = synchronized(decisionsFile) {
        listOf(DataDir.file("fit_alert_decisions.old.jsonl"), decisionsFile).filter { it.exists() }.flatMap { it.readLines() }
            .mapNotNull { runCatching { json.decodeFromString(FitAlertDecision.serializer(), it) }.getOrNull() }
            .filter { it.savedSearchId == productId }.asReversed().take(most)
    }
}
