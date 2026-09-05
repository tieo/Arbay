package io.github.tieo.arbay.crawler

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

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
    private val dir = File(System.getProperty("user.home"), ".arbay/error_snapshots")
    private val sinceLastPrune = java.util.concurrent.atomic.AtomicInteger(0)
    private val json = Json { prettyPrint = true; encodeDefaults = true }

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
        val id = "${platform.lowercase()}_${ts.epochSeconds}_${(ts.nanosecondsOfSecond / 1_000_000) % 1000}"

        // Save HTML if available
        var htmlFile: String? = null
        if (!html.isNullOrBlank()) {
            htmlFile = "$id.html"
            try {
                File(dir, htmlFile).writeText(html)
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
            File(dir, "$id.json").writeText(json.encodeToString(snapshot))
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

    fun get(id: String): ErrorSnapshot? {
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
            File(dir, "$id.json").writeText(json.encodeToString(updated))
        } catch (_: Exception) {}
    }

    /** Prune old resolved snapshots (keep last 200 unresolved, 50 resolved) */
    fun prune() {
        val all = dir.listFiles { f -> f.extension == "json" }
            ?.sortedByDescending { it.lastModified() } ?: return
        val toDelete = mutableListOf<File>()
        var unresolvedCount = 0
        var resolvedCount = 0
        for (f in all) {
            val snapshot = try { json.decodeFromString<ErrorSnapshot>(f.readText()) } catch (_: Exception) { continue }
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
