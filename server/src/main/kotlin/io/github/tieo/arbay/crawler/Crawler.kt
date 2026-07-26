package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.VehicleInfo
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

interface Crawler {
    val platformId: PlatformId

    suspend fun search(query: SearchQuery): List<Listing>

    /** Fetch a single listing's detail page and return its verified vehicle specs, where the
     *  detail page carries structured attributes the search card omits (power, gearbox, doors,
     *  emission, colour…). Default null: the platform's card already holds everything it knows. */
    suspend fun fetchDetailVehicle(listing: Listing): VehicleInfo? = null
}

private val queryExpansionEnabled =
    System.getenv("ARBAY_QUERY_EXPANSION")?.equals("on", true) == true

/**
 * Search the query, then — where enabled — the market's other names for the same thing, merged.
 *
 * A marketplace matches the query as a literal word, so a search misses everything its sellers
 * named differently: Kleinanzeigen answers "Parkettschleifmaschine" only with machines titled
 * exactly that, never the ones titled "Parkettschleifer". The words to try come from the market
 * itself where it prints them, and are inferred from the results where it does not. What the wider
 * net drags in is left to the relevance filter, as for any other result.
 *
 * The query itself decides the platform's health, so its failure propagates while a follow-up
 * search that fails is simply dropped.
 */
suspend fun Crawler.searchAllSpellings(
    query: SearchQuery,
    background: QueryVariants.TermBackground,
): List<Listing> {
    val log = LoggerFactory.getLogger("Crawler[${platformId.displayName}]")
    val suggested = mutableListOf<String>()
    val primary = withContext(SuggestedTermsEmitter { suggested += it }) { search(query) }

    // Off unless ARBAY_QUERY_EXPANSION=on. Widening a search reaches listings the query's own
    // wording never could, but which of a market's related searches names the same thing and which
    // names the tool beside it cannot be told apart before issuing them: measured on Kleinanzeigen,
    // the true synonym and the adjacent machine are indistinguishable by frequency, by overlap with
    // the first result set, and by embedding distance. Until that is settled it stays a choice.
    if (!queryExpansionEnabled) return primary

    // What the market itself calls the thing beats anything inferred from its results: those terms
    // are its own vocabulary, and on the sites that print them they cost no request. Inference is
    // the fallback for the sites that print nothing.
    val terms = QueryVariants.rank(suggested, query.text, primary)
        .ifEmpty { QueryVariants.candidatesFrom(primary, query.text, background) }

    val extra = terms.flatMap { term ->
        val followUp = runCatching { search(query.copy(text = term)) }.getOrDefault(emptyList())
        log.info("{}: '{}' also searched as '{}' (+{} listings)",
            platformId.displayName, query.text, term,
            followUp.count { l -> primary.none { it.id == l.id } })
        followUp
    }
    return (primary + extra).distinctBy { it.id }
}

suspend fun Crawler.trackedSearch(
    query: SearchQuery,
    background: QueryVariants.TermBackground,
): List<Listing> {
    val log = LoggerFactory.getLogger("Crawler[${platformId.displayName}]")
    CrawlerStatusTracker.recordAttempt(platformId)
    return try {
        val results = searchAllSpellings(query, background)
        if (results.isEmpty()) {
            CrawlerStatusTracker.recordError(platformId, "Search returned 0 results", ErrorType.EMPTY_RESULTS)
            log.warn("{}: 0 results for '{}'", platformId.displayName, query.text)
        } else {
            val irrelevance = RelevanceFilter.irrelevanceReport(results, query)
            if (irrelevance != null) {
                CrawlerStatusTracker.recordError(platformId, irrelevance, ErrorType.IRRELEVANT_RESULTS)
                val snapId = ErrorSnapshotStore.capture(
                    platform = platformId.name, query = query.text,
                    error = CrawlerBlockedException(irrelevance, ErrorType.IRRELEVANT_RESULTS),
                    errorType = ErrorType.IRRELEVANT_RESULTS,
                )
                log.warn("{}: {} [snapshot:{}]", platformId.displayName, irrelevance, snapId)
            } else {
                CrawlerStatusTracker.recordSuccess(platformId, results.size)
                log.info("{}: {} results for '{}'", platformId.displayName, results.size, query.text)
            }
        }
        results
    } catch (e: CrawlerBlockedException) {
        CrawlerStatusTracker.recordError(platformId, e.message ?: "Blocked", e.errorType)
        if (BlockCooldown.isBlock(e.errorType)) BlockCooldown.record(platformId)
        val snapId = ErrorSnapshotStore.capture(
            platform = platformId.name, query = query.text, error = e, errorType = e.errorType,
        )
        log.warn("{}: blocked — {} [snapshot:{}]", platformId.displayName, e.message, snapId)
        emptyList()
    } catch (e: java.util.concurrent.CancellationException) {
        // Coroutine cancelled (client disconnected) — not a real crawler error
        log.debug("{}: cancelled for '{}'", platformId.displayName, query.text)
        emptyList()
    } catch (e: Exception) {
        // A cancelling parent scope (the client disconnected mid-search) surfaces as an
        // exception whose message mentions Cancelling/Cancelled but is not a CancellationException.
        // That is not a crawler failure — don't record it as an error or a snapshot.
        if (e.message?.contains("Cancelling") == true || e.message?.contains("Cancelled") == true) {
            log.debug("{}: cancelled — {}", platformId.displayName, e.message)
            return emptyList()
        }
        val errorType = classifyException(e)
        CrawlerStatusTracker.recordError(platformId, e.message ?: "Unknown error", errorType)
        if (BlockCooldown.isBlock(errorType)) BlockCooldown.record(platformId)
        val snapId = ErrorSnapshotStore.capture(
            platform = platformId.name, query = query.text, error = e, errorType = errorType,
        )
        log.error("{}: error — {} [snapshot:{}]", platformId.displayName, e.message, snapId)
        emptyList()
    }
}

class CrawlerBlockedException(
    message: String,
    val errorType: ErrorType,
) : RuntimeException(message)
