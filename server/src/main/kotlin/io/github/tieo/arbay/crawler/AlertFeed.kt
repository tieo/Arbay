package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * One offer a saved search's alert let through, as the watch first found it. [seq] orders every
 * alert ever recorded, so a reader can ask for what came after.
 */
@Serializable
data class FoundAlert(
    val seq: Long,
    val savedSearchId: String,
    val searchName: String,
    /** The alert rule that let it through, or "fit" with the score for a fit alert. */
    val rule: String,
    val listingId: String,
    val title: String,
    val url: String,
    /** The landed price the rule decided on, in whole euros. */
    val priceEur: Long? = null,
    val at: Instant,
)

/**
 * Every offer a saved search's alert let through, to whoever asks [after] a point: an assistant on
 * another machine hears about a find the moment the watch has it, as the phone does by its push,
 * and can act on it while the offer is still there. Kept on disk, so a reader that was away gets
 * what it missed.
 */
object AlertFeed {
    private val log = LoggerFactory.getLogger(AlertFeed::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The alerts kept for readers that were away, newest last. */
    private const val KEPT = 200

    private val file = DataDir.file("alerts_found.json")
    private var alerts: List<FoundAlert> =
        file.readStore(log) { json.decodeFromString<List<FoundAlert>>(it) } ?: emptyList()
    private val latest = MutableStateFlow(alerts.maxOfOrNull { it.seq } ?: 0L)

    /** Numbers and keeps the alerts of one run. */
    fun record(found: List<FoundAlert>) {
        if (found.isEmpty()) return
        val last = synchronized(this) {
            var seq = latest.value
            val numbered = found.map { it.copy(seq = ++seq, at = Clock.System.now()) }
            alerts = (alerts + numbered).takeLast(KEPT)
            runCatching { file.writeTextAtomically(json.encodeToString(alerts)) }
                .onFailure { log.error("could not write the alert feed: {}", it.message) }
            seq
        }
        latest.value = last
    }

    /** The alerts recorded after [after], waiting up to [wait] for one when there are none yet. */
    suspend fun after(after: Long, wait: Duration): List<FoundAlert> {
        if (latest.value <= after && wait.isPositive()) withTimeoutOrNull(wait) { latest.first { it > after } }
        return synchronized(this) { alerts.filter { it.seq > after } }
    }
}
