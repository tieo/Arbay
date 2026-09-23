package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Captures full debug snapshots when crawler errors occur.
 * Each snapshot includes everything needed to reproduce and debug the error.
 * Stored as JSON metadata + raw HTML in ~/.arbay/error_snapshots/.
 *
 * Snapshots are auto-loaded by AI assistants for debugging.
 * Users reference them by short ID shown in the app error display.
 */
object ErrorSnapshotStore {
    private val log = LoggerFactory.getLogger(ErrorSnapshotStore::class.java)
    private val dir = DataDir.file("error_snapshots")
    // Starts at the threshold so the first capture after a restart prunes: whatever collected while
    // no version of this pruned is cleared then, rather than fifty captures later.
    private val sinceLastPrune = java.util.concurrent.atomic.AtomicInteger(50)
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    init { dir.mkdirs() }

    @Serializable
    data class ErrorSnapshot(
        val id: String,
        val timestamp: Instant,
        val platform: String,
        val query: String,
        val url: String? = null,
        val fetchStage: String? = null,
        val errorType: String,
        val errorMessage: String,
        val stackTrace: String,
        val htmlLength: Int = 0,
        val htmlFile: String? = null,
        val finalUrl: String? = null,
        val statusCode: Int? = null,
        val resolved: Boolean = false,
    )

    private val sequence = AtomicInteger()

    fun capture(
        platform: String,
        query: String,
        error: Exception,
        errorType: ErrorType,
        url: String? = null,
        fetchStage: String? = null,
        html: String? = null,
        finalUrl: String? = null,
        statusCode: Int? = null,
    ): String {
        val ts = Clock.System.now()
        // The sequence keeps two captures from the same market in the same millisecond, which
        // parallel spellings of one search do produce, from writing over each other.
        val id = "${platform.lowercase()}_${ts.epochSeconds}_${(ts.nanosecondsOfSecond / 1_000_000) % 1000}_${sequence.incrementAndGet() % 1000}"

        // Save HTML if available
        var htmlFile: String? = null
        if (!html.isNullOrBlank()) {
            htmlFile = "$id.html"
            try {
                File(dir, htmlFile).writeTextAtomically(html)
            } catch (e: Exception) {
                log.warn("Failed to save error HTML: {}", e.message)
                htmlFile = null
            }
        }

        val snapshot = ErrorSnapshot(
            id = id,
            timestamp = ts,
            platform = platform,
            query = query,
            url = url,
            fetchStage = fetchStage,
            errorType = errorType.name,
            errorMessage = error.message ?: "Unknown",
            stackTrace = error.stackTraceToString().take(3000),
            htmlLength = html?.length ?: 0,
            htmlFile = htmlFile,
            finalUrl = finalUrl,
            statusCode = statusCode,
        )

        try {
            File(dir, "$id.json").writeTextAtomically(json.encodeToString(snapshot))
            log.info("Error snapshot saved: {} ({})", id, errorType)
        } catch (e: Exception) {
            log.warn("Failed to save error snapshot: {}", e.message)
        }

        // [prune] sets the size of this directory and nothing was calling it: 1,688 snapshots had
        // collected against a limit of 250, and each one now keeps the page it was refused with.
        // Every 50th capture, since pruning reads each snapshot back and there is no sense doing
        // that on every single one.
        if (sinceLastPrune.incrementAndGet() >= 50) {
            sinceLastPrune.set(0)
            runCatching { prune() }
        }

        return id
    }

    fun list(resolved: Boolean? = null, limit: Int = 50): List<ErrorSnapshot> {
        return dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit * 2) // over-fetch to account for filtering
            ?.mapNotNull { f ->
                try { json.decodeFromString<ErrorSnapshot>(f.readText()) } catch (_: Exception) { null }
            }
            ?.filter { resolved == null || it.resolved == resolved }
            ?.take(limit)
            ?: emptyList()
    }

    // Ids are minted by capture() as platform_seconds_millis. Anything else arriving from a
    // request is refused before it becomes part of a path, so it cannot name a file outside
    // the snapshot directory.
    private val snapshotId = Regex("[a-z0-9_]+")


    fun get(id: String): ErrorSnapshot? {
        if (!snapshotId.matches(id)) return null
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try { json.decodeFromString(f.readText()) } catch (_: Exception) { null }
    }

    fun getHtml(id: String): String? {
        val snapshot = get(id) ?: return null
        val htmlFile = snapshot.htmlFile ?: return null
        val f = File(dir, htmlFile)
        return if (f.exists()) f.readText() else null
    }

    fun markResolved(id: String) {
        val snapshot = get(id) ?: return
        val updated = snapshot.copy(resolved = true)
        try {
            File(dir, "$id.json").writeTextAtomically(json.encodeToString(updated))
        } catch (e: Exception) {
            log.warn("Could not mark snapshot {} resolved: {}", id, e.message)
        }
    }

    /** Prune old resolved snapshots (keep last 200 unresolved, 50 resolved) */
    fun prune() {
        val all = dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() } ?: return
        val toDelete = mutableListOf<File>()
        var unresolvedCount = 0
        var resolvedCount = 0
        for (f in all) {
            // A snapshot that cannot be read back is of no use to anyone and would otherwise
            // never be counted against either limit, so it goes first.
            val snapshot = try { json.decodeFromString<ErrorSnapshot>(f.readText()) } catch (_: Exception) {
                toDelete.add(f)
                continue
            }
            if (snapshot.resolved) {
                resolvedCount++
                if (resolvedCount > 50) toDelete.add(f)
            } else {
                unresolvedCount++
                if (unresolvedCount > 200) toDelete.add(f)
            }
        }
        for (f in toDelete) {
            f.delete()
            // Also delete associated HTML file
            val htmlF = File(dir, f.nameWithoutExtension + ".html")
            if (htmlF.exists()) htmlF.delete()
        }
    }
}
