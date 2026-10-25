package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** A site whose page sells something and that Arbay could not read an offer off. */
@Serializable
data class SiteGap(
    val seq: Long,
    val domain: String,
    /** The page that could not be read; its HTML as fetched is at `GET /api/web/gaps/{domain}/page`. */
    val url: String,
    /** The search that found it. */
    val query: String,
    /** Whether the site has a recipe that found nothing here, rather than none at all. */
    val recipeBroken: Boolean,
    val at: Instant,
)

/**
 * The sites the web market found selling something and could not read, to whoever asks [after] a
 * point: the agent reached through this feed writes each site its [SiteRecipe], so the next search
 * reads it. A site is reported once a day at most, and not while a recipe for it exists and works.
 * The page as fetched is kept beside the report, since the agent has to see what Arbay saw: a shop
 * may answer a browser differently from Arbay's fetch.
 */
object SiteGaps {
    private val log = LoggerFactory.getLogger(SiteGaps::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val KEPT = 300
    private val REPORT_AGAIN_AFTER = 24.hours

    private val file = DataDir.file("web_gaps.json")
    private val pages = DataDir.file("web_gaps").apply { mkdirs() }
    private var gaps: List<SiteGap> = file.readStore(log) { json.decodeFromString<List<SiteGap>>(it) } ?: emptyList()
    private val latest = MutableStateFlow(gaps.maxOfOrNull { it.seq } ?: 0L)

    fun record(domain: String, url: String, query: String, html: String, recipeBroken: Boolean) {
        val now = Clock.System.now()
        val seq = synchronized(this) {
            val last = gaps.lastOrNull { it.domain == domain }
            if (last != null && now - last.at < REPORT_AGAIN_AFTER && last.recipeBroken == recipeBroken) return
            val next = latest.value + 1
            gaps = (gaps + SiteGap(next, domain, url, query, recipeBroken, now)).takeLast(KEPT)
            runCatching {
                pageFile(domain).writeText(html)
                file.writeTextAtomically(json.encodeToString(gaps))
            }.onFailure { log.error("site gap not written: {}", it.message) }
            next
        }
        log.info("Web: {} sells something Arbay cannot read yet ({}), reported as gap {}", domain, url, seq)
        latest.value = seq
    }

    /** The gaps recorded after [after], waiting up to [wait] for one when there are none yet. */
    suspend fun after(after: Long, wait: Duration): List<SiteGap> {
        if (latest.value <= after && wait.isPositive()) withTimeoutOrNull(wait) { latest.first { it > after } }
        return synchronized(this) { gaps.filter { it.seq > after } }
    }

    /** The page as Arbay fetched it when it reported [domain]. */
    fun page(domain: String): String? = pageFile(domain).takeIf { it.isFile }?.readText()

    private fun pageFile(domain: String) = File(pages, domain.replace(Regex("[^a-z0-9.-]"), "_") + ".html")
}
