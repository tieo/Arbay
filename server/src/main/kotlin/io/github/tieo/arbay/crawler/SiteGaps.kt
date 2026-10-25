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

    /** Every page found unreadable lately, per site with the words that found it, reported or
     *  not: learning a site reads each of them again, for each search that met the site. */
    private val pagesSeen = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Pair<String, String>>>()

    fun record(domain: String, url: String, query: String, html: String, recipeBroken: Boolean) {
        val now = Clock.System.now()
        pagesSeen.computeIfAbsent(domain) { java.util.Collections.synchronizedSet(LinkedHashSet()) }.let { seen ->
            seen += url to query
            while (seen.size > PAGES_KEPT_PER_SITE) seen.remove(seen.first())
        }
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

    /** Every page found unreadable on [domain], with the words of the search that found it. */
    fun of(domain: String): List<Pair<String, String>> =
        (pagesSeen[domain]?.toList().orEmpty() + synchronized(this) { gaps.filter { it.domain == domain }.map { it.url to it.query } }).distinct()

    /**
     * What learning a site gave each search that met it, as each is learned: the searches still
     * open wait on this to show the offers. [offers] is keyed by the words of the search; empty
     * where the site was given up on.
     */
    class Learned(val domain: String, val offers: Map<String, List<io.github.tieo.arbay.model.Listing>>)

    val learned = kotlinx.coroutines.flow.MutableSharedFlow<Learned>(extraBufferCapacity = 64)

    private const val PAGES_KEPT_PER_SITE = 50

    /**
     * The sites given up on: they sell nothing after all (dielen-schleifen.de quotes Bauhaus's
     * rental prices beside its sanding paper) or block every way in. Their pages are neither
     * reported again nor guessed at, which read the quoted price as the site's own offer, until
     * a recipe for the site is kept.
     */
    private val givenUpFile = DataDir.file("web_given_up.json")
    private var givenUp: Set<String> = givenUpFile.readStore(log) { json.decodeFromString<Set<String>>(it) } ?: emptySet()

    fun isGivenUp(domain: String): Boolean = synchronized(this) { domain in givenUp }

    fun giveUp(domain: String) = setGivenUp(givenUp + domain)

    fun takeBack(domain: String) { if (isGivenUp(domain)) setGivenUp(givenUp - domain) }

    private fun setGivenUp(domains: Set<String>) = synchronized(this) {
        givenUp = domains
        runCatching { givenUpFile.writeTextAtomically(json.encodeToString(domains)) }.onFailure { log.error("given-up sites not written: {}", it.message) }
    }

    /** The page as Arbay fetched it when it reported [domain]. */
    fun page(domain: String): String? = pageFile(domain).takeIf { it.isFile }?.readText()

    private fun pageFile(domain: String) = File(pages, domain.replace(Regex("[^a-z0-9.-]"), "_") + ".html")
}
