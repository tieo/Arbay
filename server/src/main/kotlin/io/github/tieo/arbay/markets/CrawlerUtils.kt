package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SuggestedTerm
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.tieo.arbay.relevance.QueryVariants
import io.github.tieo.arbay.relevance.RelevanceFilter

internal fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

/** Randomized inter-page delay (150-600 ms) so paged fetches don't leave a fixed-interval trail. */
private fun fetchJitterMs(): Long = Random.nextLong(150, 600)



/** CoroutineContext element for emitting fetch-engine progress to the SSE stream */
class FetchProgressEmitter(val emit: suspend (stage: String) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<FetchProgressEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

/** CoroutineContext element for streaming a page's parsed listings to the SSE stream as soon as it
 *  is parsed, so a slow multi-page crawler (mobile.de under the stealth browser) surfaces its first
 *  page immediately instead of dumping everything at the end. Absent outside the streaming search,
 *  where [emitPartialResults] is a no-op and the crawler's return value is used as before. */
class PartialResultEmitter(val emit: suspend (listings: List<Listing>) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PartialResultEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

/** Stream one just-parsed page of [listings] to the live result feed if a [PartialResultEmitter] is
 *  attached; a no-op otherwise. Crawlers call this after each page so results pipeline in. */
internal suspend fun emitPartialResults(listings: List<Listing>) {
    if (listings.isEmpty()) return
    coroutineContext[CrawlClock]?.answered()
    coroutineContext[PartialResultEmitter]?.emit(listings)
}

/** CoroutineContext element collecting the related search terms a marketplace prints on its own
 *  results page ("Ähnliche Suchanfragen"). The market's own vocabulary for the thing searched for,
 *  which is what [QueryVariants] would otherwise have to infer. */
class SuggestedTermsEmitter(val emit: (terms: List<String>) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<SuggestedTermsEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

/** Report the related search terms found on a results page, if anyone is collecting them. Crawlers
 *  call this while parsing, so the terms cost no request of their own. */
internal suspend fun emitSuggestedTerms(terms: List<String>) {
    if (terms.isEmpty()) return
    coroutineContext[SuggestedTermsEmitter]?.emit(terms)
}

/** CoroutineContext element collecting the terms a market was actually searched with: the one
 *  handed to it, plus every other name for the thing that the search was allowed to follow up on.
 *  Reported to the app, which shows them per market — a term nobody can see is a term nobody can
 *  hold the answer to. */
class TermsUsedEmitter(val emit: (term: String) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TermsUsedEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

internal suspend fun emitTermUsed(term: String) {
    coroutineContext[TermsUsedEmitter]?.emit(term)
}

/**
 * Whether a market's answer looks like a parser reading the wrong thing.
 *
 * Two of these ran for months: eBay's "New Listing" flag and its "opens in a new window or tab"
 * line, each read as the title of every listing that carried it. Nothing noticed, because a title
 * that is not the listing's title still parses, and the listings were then dropped one by one for
 * carrying none of the words searched for. A market answering with the same title over and over is
 * the shape of that, whatever the cause, and it is worth saying out loud.
 *
 * Unless the repeated title is the thing searched for: mobile.de titles a listing with its make
 * and model and nothing else, so the T6s answering "vw t6 california" read "Volkswagen T6 Kombi"
 * one after the other, each a different van. A title carrying a word of the search is the market answering.
 */
fun repeatedTitleReport(listings: List<Listing>, query: SearchQuery): String? {
    if (listings.size < MIN_ANSWER_TO_JUDGE_TITLES) return null
    val (title, count) = listings
        .groupingBy { it.title.trim().lowercase() }
        .eachCount()
        .maxByOrNull { it.value } ?: return null
    if (count < MIN_ANSWER_TO_JUDGE_TITLES) return null
    if (count.toDouble() / listings.size < SAME_TITLE_SHARE) return null
    val example = listings.first { it.title.trim().lowercase() == title }
    if (RelevanceFilter.carriesAWordOfTheSearch(example, query)) return null
    return "$count of ${listings.size} listings are titled \"$title\" — the title is being read " +
        "off the wrong part of the card"
}

private const val MIN_ANSWER_TO_JUDGE_TITLES = 5
private const val SAME_TITLE_SHARE = 0.5

/**
 * A market saying it answered by matching the search to one of its own categories.
 *
 * Idealo answers "laptop" with the Laptop category, whose products are MacBooks and ThinkPads that
 * never write the word. That is the market having searched, and having answered well; judged on
 * words alone it looks exactly like a market that ignored the question, and the whole answer was
 * being thrown away.
 */
class CategoryAnswerEmitter(val emit: () -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CategoryAnswerEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

internal suspend fun emitAnsweredFromCategory() {
    coroutineContext[CategoryAnswerEmitter]?.emit()
}

/** CoroutineContext element collecting the app's verdict on each other word a market printed:
 *  what it is, why, and — once a word has actually been searched — what it added. A word emitted
 *  twice is the same word with its outcome filled in, so a collector keeps the last of each. */
class TermVerdictEmitter(val emit: (term: SuggestedTerm) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TermVerdictEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

internal suspend fun emitTermVerdict(term: SuggestedTerm) {
    coroutineContext[TermVerdictEmitter]?.emit(term)
}

/**
 * The clock of one market's time limit, which stands still while the crawl waits for the market's
 * request window ([RequestMonitor.awaitBudget]) or a browser that other searches hold, and starts
 * over with every page the market answers ([answered]). The limit is there for a crawl that never
 * answers. A crawl queued behind other searches is not that: a Crafter search reading
 * Kleinanzeigen while three others did found the window spent, waited for it to open the vans'
 * ads, and was given up on with none of them read. Nor is one reading a long price band page after
 * page: Kleinanzeigen's 1,390 Crafters between 10,000 and 30,990 € take longer than the limit, and
 * the 275 vans streamed by then were thrown away with it. How long a crawl waits is the waiter's
 * own limit.
 */
class CrawlClock internal constructor(private val limitMs: Long) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CrawlClock>
    override val key: CoroutineContext.Key<*> = Key

    private var waits = 0
    private val standingStill = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val answers = kotlinx.coroutines.flow.MutableStateFlow(0L)

    private fun settle() { standingStill.value = waits > 0 }

    /** Runs [block] with the clock standing still, for time spent waiting on other searches. */
    suspend fun <T> waitingOnOthers(block: suspend () -> T): T {
        synchronized(this) { waits++; settle() }
        try {
            return block()
        } finally {
            synchronized(this) { waits--; settle() }
        }
    }

    /** The market answered a page: the clock starts over. */
    fun answered() = answers.update { it + 1 }

    /** Returns once [limitMs] have passed with the clock running and no page answered. */
    internal suspend fun runOut() {
        var left = limitMs
        var seen = answers.value
        while (left > 0) {
            standingStill.first { !it }
            val from = System.currentTimeMillis()
            kotlinx.coroutines.withTimeoutOrNull(left) {
                kotlinx.coroutines.flow.combine(standingStill, answers) { still, n -> still || n != seen }.first { it }
            }
            if (answers.value != seen) {
                seen = answers.value
                left = limitMs
            } else left -= System.currentTimeMillis() - from
        }
    }
}

/**
 * Runs [block] under a [CrawlClock] of [limitMs] and returns its result, or null when the clock
 * ran out first and [block] was cancelled. A cancellation from outside (the app hung up) carries on.
 */
suspend fun <T> withCrawlLimitOrNull(limitMs: Long, block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T? {
    val clock = CrawlClock(limitMs)
    val ranOut = java.util.concurrent.atomic.AtomicBoolean(false)
    return kotlinx.coroutines.coroutineScope {
        val work = async(clock) { block() }
        val limit = launch {
            clock.runOut()
            ranOut.set(true)
            work.cancel()
        }
        try {
            work.await()
        } catch (e: CancellationException) {
            if (ranOut.get()) null else throw e
        } finally {
            limit.cancel()
        }
    }
}

/** Runs the page-by-page crawl every list crawler shares: fetch and parse one page via [fetchPage],
 *  stopping when a page comes back empty, adds nothing new, or the per-platform cap is reached.
 *  Deduplicates by [Listing.externalId] (keeping first-seen order), streams each page's freshly
 *  parsed listings to the live feed as they arrive, and returns the full ordered set. A block on the
 *  first page propagates (the platform failed); a block on a later page just stops paging and keeps
 *  what was already collected. Crawlers supply only how to fetch+parse a single page number; the
 *  loop, dedup, cap, block handling and streaming live in this one place. [page] counts from
 *  [SearchQuery.startPage]. */
/** One page, retried once on a thrown error before it is believed failed. A single transient
 *  403/503 on page two used to end pagination and silently drop the rest of a platform's results;
 *  one backed-off retry recovers those. An empty page is NOT retried — it is the normal end of
 *  results, and retrying it would double the tail fetch for every platform on every search.
 *  Returns null when the page could not be fetched after the retry: the first page rethrows (the
 *  platform genuinely failed), a later one just stops paging and keeps what was already collected. */
private suspend fun fetchPageWithRetry(
    page: Int,
    isFirstPage: Boolean,
    fetchPage: suspend (page: Int) -> List<Listing>,
): List<Listing>? {
    try {
        return fetchPage(page)
    } catch (e: CrawlerBlockedException) {
        // A hard block (captcha / IP ban) is not transient; escalating fetch tiers already ran.
        // Retrying would only deepen the block, so surface it immediately.
        if (isFirstPage) throw e
        return null
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        // A transient network/HTTP error: back off and try once more.
    }
    delay(700L + fetchJitterMs())
    return try {
        fetchPage(page)
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        if (isFirstPage) throw e
        null
    }
}

/** How many pages this search may fetch: the search's own limit when it set one, else the
 *  configured search depth, never more than [cap] for a site that blocks past a few pages. A
 *  search that asks for more offers than usual ([SearchQuery.maxResults]) is not held to the
 *  usual page count either: it reads until it has them, or the market runs dry. */
internal fun SearchQuery.pageLimit(cap: Int = Int.MAX_VALUE): Int =
    (maxPages ?: if (maxResults != null) DEEP_SEARCH_PAGES else CrawlerConfig.current.maxPages).coerceAtMost(cap)

/** The most pages a search asking for more offers than usual reads from one market. */
internal const val DEEP_SEARCH_PAGES = 50

/** The most offers a search reads from one market when it reads the whole of its price band.
 *  Kleinanzeigen's Nutzfahrzeuge held 1,390 Crafters between 10,000 and 30,990 €, and a cap of
 *  1,000 ended the read at 24,990 € with the search's own ideal price at 25,000 €. */
internal const val WHOLE_BAND_RESULTS = 2000

/** This search reading every offer between its prices, for a market that sorts by price and caps
 *  the price itself ([SortsByPrice]): where the search names a ceiling and no offer count of its
 *  own, the count stops being the limit and the ceiling is. */
internal fun SearchQuery.wholePriceBand(): SearchQuery =
    if (maxPrice != null && maxResults == null) copy(maxResults = WHOLE_BAND_RESULTS) else this

/** How many offers to read from each market for this search. */
internal fun SearchQuery.resultCap(): Int = maxResults ?: CrawlerConfig.current.maxResultsPerPlatform

internal suspend fun paginate(
    query: SearchQuery,
    fetchPage: suspend (page: Int) -> List<Listing>,
): List<Listing> {
    val maxPages = query.pageLimit()
    val seen = LinkedHashMap<String, Listing>()
    for (offset in 0 until maxPages) {
        val listings = fetchPageWithRetry(query.startPage + offset, isFirstPage = offset == 0, fetchPage)
            ?: break
        if (listings.isEmpty()) break
        emitPartialResults(listings)
        val newIds = listings.count { it.externalId !in seen }
        listings.forEach { seen.putIfAbsent(it.externalId, it) }
        if (newIds == 0) break
        if (seen.size >= query.resultCap()) break
    }
    return seen.values.toList()
}

/**
 * The page arrived but not in the shape its parser reads, which is what a market redesign looks
 * like. Reported as a parse error with the page attached for the snapshot: returning no listings
 * instead made a broken parser indistinguishable from a search nothing matched.
 */
internal fun unreadablePage(platformName: String, what: String, page: String, cause: Exception? = null) =
    CrawlerBlockedException(
        "$platformName: $what" + (cause?.message?.let { " (${it.take(120)})" } ?: ""),
        ErrorType.PARSE_ERROR,
        html = page.take(500_000),
    )



internal fun classifyException(e: Exception): ErrorType {
    val msg = e.message?.lowercase() ?: ""
    return when {
        e is io.ktor.client.network.sockets.ConnectTimeoutException -> ErrorType.TIMEOUT
        e is io.ktor.client.plugins.HttpRequestTimeoutException -> ErrorType.TIMEOUT
        msg.contains("timeout") -> ErrorType.TIMEOUT
        msg.contains("connection refused") || msg.contains("unreachable") -> ErrorType.NETWORK_ERROR
        else -> ErrorType.UNKNOWN
    }
}

