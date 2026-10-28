package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.OfferKind
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Reads each kept offer against the words of its search with Claude Haiku, twice, through the
 * Claude Code CLI signed in to the user's account, and says what both readings agree the offer is.
 *
 * Words alone cannot tell a grigri belay device from a "grigri de sac" bag charm, or a Dusch-WC
 * from its remote: about a quarter of the offers that carry a word of the search are something
 * else. Measured on 2,257 labelled offers of 35 real searches, one reading marked 4 of 683 real
 * offers as something else (a phone "neuer Bildschirm" read as a screen); two readings that have to
 * agree caught 410 of 479 wrong offers and one borderline real one, and Sonnet as the second
 * reader did little better at ten times the price. So every offer is read once, what the first
 * reading did not call the thing (and what the word rule put last, to bring the real ones back) is
 * read again, and an offer is moved only on agreement. Nothing is hidden: a marked offer goes
 * last, as [Listing.offQuery] has it.
 *
 * Verdicts are kept per search words and offer on disk, so a search run again, or a saved search
 * watched every hour, asks only about offers it has not seen. Off where the CLI has no account to
 * use (no `CLAUDE_CODE_OAUTH_TOKEN`), as in tests.
 */
object OfferJudge {
    private val log = LoggerFactory.getLogger(OfferJudge::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** The prompt the measurement above was made with; a change to it wants measuring again. */
    private val SYSTEM = """
        You sort second-hand and shop offers that a market returned for a buyer's search.
        For each offer decide what a buyer who typed exactly these search words gets:
        THING: the item the words ask for, in any condition (new, used, defective, for parts), alone or in a bundle that contains it, any variant or size the words leave open.
        ACCESSORY: a part, spare, consumable, case, cover, cable, mount or add-on for that item, or a manual or empty box, but not the item itself.
        OTHER: something else, e.g. a different product that shares a word (a band, a book, a toy, a bag charm), or an item of another kind.
        Words may be German, English, French, Italian or other languages; brand names and model numbers count. When the title does not let you tell, answer UNSURE.
        Answer only with a JSON array of objects {"i": <offer number>, "v": "THING"|"ACCESSORY"|"OTHER"|"UNSURE"}, one per offer, nothing else.
    """.trimIndent()

    /** The current Haiku, by the CLI's alias. */
    private const val MODEL = "haiku"

    /** Offers per call: the measured batch size, small enough that no answer runs long. */
    private const val BATCH = 50

    /** A call that has not answered by then is given up; its offers stay as the word rule left them. */
    private const val CALL_LIMIT_MS = 180_000L

    /** Verdicts older than this are read again: a title stays, but the models get better. */
    private const val KEEP_MS = 30L * 24 * 3600 * 1000

    private const val WRITE_EVERY_MS = 30_000L

    // A handful of CLI processes at once: each is a whole Claude Code process, and searches come in together.
    private val calls = Semaphore(3)

    @Serializable
    private data class Verdict(val first: String, val second: String? = null, val at: Long)

    @Serializable
    private data class Persisted(val verdicts: Map<String, Verdict> = emptyMap())

    private val verdicts = ConcurrentHashMap<String, Verdict>()
    private var file: File? = null
    @Volatile private var lastWrite = 0L

    val enabled: Boolean get() = file != null

    /** Switched on by the server at start where the CLI has an account to use. */
    fun install(store: File) {
        if (System.getenv("CLAUDE_CODE_OAUTH_TOKEN").isNullOrBlank()) {
            log.info("Offer judge off: no CLAUDE_CODE_OAUTH_TOKEN")
            return
        }
        val now = System.currentTimeMillis()
        store.readStore(log) { json.decodeFromString(Persisted.serializer(), it) }?.verdicts
            ?.filterValues { now - it.at < KEEP_MS }?.let(verdicts::putAll)
        file = store
        log.info("Offer judge on, {} verdicts kept", verdicts.size)
    }

    /**
     * [listings] with what both readings make of each, for the search [words]. Offers not read
     * yet are asked about first, which takes a call or two per fifty offers; the rest come
     * from what is kept.
     */
    suspend fun judge(listings: List<Listing>, words: String): List<Listing> {
        if (!enabled || listings.isEmpty() || words.isBlank()) return listings
        val asked = words.trim().lowercase()
        fun key(l: Listing) = "$asked\u0001${l.id}\u0001${l.title}"

        val unseen = listings.filter { verdicts[key(it)] == null }.distinctBy { key(it) }
        ask(unseen, words).forEach { (l, v) -> verdicts[key(l)] = Verdict(first = v, at = System.currentTimeMillis()) }

        // Read again what could move: what the first reading did not call the thing, and what the
        // word rule put last while it did.
        val again = listings.filter { l ->
            val v = verdicts[key(l)] ?: return@filter false
            v.second == null && (v.first in JUNK || (v.first == "THING" && l.offQuery))
        }.distinctBy { key(it) }
        ask(again, words).forEach { (l, v) -> verdicts[key(l)]?.let { verdicts[key(l)] = it.copy(second = v) } }
        persistNowAndThen()

        return listings.map { l ->
            val v = verdicts[key(l)] ?: return@map l
            when {
                v.first in JUNK && v.second in JUNK ->
                    l.copy(offQuery = true, judged = if (v.first == "ACCESSORY" && v.second == "ACCESSORY") OfferKind.ACCESSORY else OfferKind.OTHER)
                v.first == "THING" && v.second == "THING" -> l.copy(offQuery = false, judged = OfferKind.THING)
                else -> l
            }
        }
    }

    private val JUNK = setOf("OTHER", "ACCESSORY")

    /** Each offer of [offers] with the model's verdict, for the offers it answered about. */
    private suspend fun ask(offers: List<Listing>, words: String): List<Pair<Listing, String>> =
        if (offers.isEmpty()) emptyList() else coroutineScope {
            offers.chunked(BATCH).map { batch -> async { calls.withPermit { call(batch, words) } } }.awaitAll().flatten()
        }

    private suspend fun call(batch: List<Listing>, words: String): List<Pair<Listing, String>> {
        val lines = batch.mapIndexed { n, l -> "$n. ${l.title.replace('\n', ' ')} | ${l.effectivePrice.amount / 100.0} EUR | ${l.platformId.name}" }
        val prompt = "Search words: $words\n\nOffers:\n${lines.joinToString("\n")}"
        val args = listOf(
            "claude", "-p", "--model", MODEL, "--setting-sources", "", "--strict-mcp-config",
            "--no-session-persistence", "--tools", "", "--output-format", "json",
            "--system-prompt", SYSTEM, prompt,
        )
        return try {
            val out = withTimeoutOrNull(CALL_LIMIT_MS) { runProcess(args) } ?: run {
                log.warn("Offer judge gave no answer for {} offers of '{}'", batch.size, words)
                return emptyList()
            }
            val result = json.parseToJsonElement(out.stdout.decodeToString()).jsonObject["result"]?.jsonPrimitive?.content
            if (out.exitCode != 0 || result == null) {
                log.warn("Offer judge failed ({}): {}", out.exitCode, (result ?: out.stderr).take(200))
                return emptyList()
            }
            val answer = json.parseToJsonElement(result.substring(result.indexOf('['), result.lastIndexOf(']') + 1)).jsonArray
            answer.mapNotNull { o ->
                val i = o.jsonObject["i"]?.jsonPrimitive?.int ?: return@mapNotNull null
                val v = o.jsonObject["v"]?.jsonPrimitive?.content ?: return@mapNotNull null
                batch.getOrNull(i)?.let { it to v }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Offer judge answer for '{}' unreadable: {}", words, e.message)
            emptyList()
        }
    }

    private fun persistNowAndThen() {
        val f = file ?: return
        val now = System.currentTimeMillis()
        if (now - lastWrite < WRITE_EVERY_MS) return
        lastWrite = now
        runCatching { f.writeTextAtomically(json.encodeToString(Persisted.serializer(), Persisted(HashMap(verdicts)))) }
            .onFailure { log.error("Offer verdicts not written: {}", it.message) }
    }
}
