package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SuggestedTerm
import io.github.tieo.arbay.model.VehicleInfo
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

interface Crawler {
    val platformId: PlatformId

    suspend fun search(query: SearchQuery): List<Listing>

    /**
     * Fetch a single listing's own page and return what it says beyond the card: the structured
     * specs (power, gearbox, doors, emission, colour…), the seller's own description, and where
     * the thing is. Default null: this market's card already holds everything it knows.
     *
     * One fetch for all three. They were separate calls, and the page was loaded twice to answer
     * two questions about the same ad.
     */
    suspend fun fetchDetail(listing: Listing): ListingDetail? = null
}

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
    emitTermUsed(query.text)
    val primary = withContext(SuggestedTermsEmitter { suggested += it }) { search(query) }

    // The market's own related searches beat anything inferred from its results; inference is the
    // fallback for the markets that print none.
    SuggestionStats.record(query.text, suggested)
    val fromMarket = QueryVariants.candidates(suggested, query.text, SuggestionStats::searchesOfferingIt)

    // Every word the market printed is reported whether or not it is searched, with the reason.
    // Costs nothing — the words came off a page already fetched — and it is the only way anyone
    // sees the rules deciding, since they reject far more than they accept.
    QueryVariants.verdicts(suggested, query.text, SuggestionStats::searchesOfferingIt)
        .forEach { emitTermVerdict(it) }

    // What is actually searched: the words picked by hand, always, and the ones the rules accept
    // only when this search asked for that. Each costs a crawl, which is why neither happens by
    // itself.
    val picked = query.reach.extraTerms
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.equals(query.text, ignoreCase = true) }
        .map { QueryVariants.Candidate(it, sharesStem = true) }
    val automatic = if (!query.reach.otherWords) emptyList() else fromMarket.ifEmpty {
        QueryVariants.candidatesFrom(primary, query.text, background)
            .map { QueryVariants.Candidate(it, sharesStem = true) }
    }
    val terms = (picked + automatic).distinctBy { it.term.lowercase() }
    if (terms.isEmpty()) return primary

    val extra = terms.flatMap { candidate ->
        // The follow-up carries its own related searches, so whether the market names this query
        // back is answered by the page we are already fetching.
        val back = mutableListOf<String>()
        val followUp = runCatching {
            withContext(SuggestedTermsEmitter { back += it }) { search(query.copy(text = candidate.term)) }
        }.getOrDefault(emptyList())

        // A word built like the query is trusted on that alone. One built differently could still
        // be the same thing ("Motorsäge" for "Kettensäge"); there the market decides, by naming the
        // query back and by asking about both words in the same company.
        val trusted = candidate.sharesStem ||
            QueryVariants.marketConfirms(query.text, candidate.term, suggested, back)
        if (!trusted) {
            log.debug("{}: dropped '{}' for '{}' — the market does not treat it as the same thing",
                platformId.displayName, candidate.term, query.text)
            return@flatMap emptyList()
        }
        emitTermUsed(candidate.term)
        val added = followUp.count { l -> primary.none { it.id == l.id } }
        emitTermVerdict(SuggestedTerm(
            term = candidate.term,
            worthTrying = true,
            why = if (candidate.term in query.reach.extraTerms) "picked by hand" else "another name for it",
            searched = true,
            added = added,
        ))
        log.info("{}: '{}' also searched as '{}' (+{} listings)",
            platformId.displayName, query.text, candidate.term, added)
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
            val ignoredSearch = RelevanceFilter.answeredSomethingElse(
                results, query, askedInItsOwnLanguage(query, platformId),
            )
            val irrelevance = ignoredSearch ?: RelevanceFilter.irrelevanceReport(results, query)
            if (irrelevance != null) {
                if (ignoredSearch != null) {
                    CrawlerStatusTracker.recordError(platformId, irrelevance, ErrorType.IRRELEVANT_RESULTS)
                } else {
                    CrawlerStatusTracker.recordSuccess(platformId, results.size)
                }
                // What came back is the evidence here — a market answering a query with its
                // catalogue is diagnosed from the titles it returned, not from a page of HTML
                // that was parsed successfully.
                val snapId = ErrorSnapshotStore.capture(
                    platform = platformId.name, query = query.text,
                    error = CrawlerBlockedException(irrelevance, ErrorType.IRRELEVANT_RESULTS),
                    errorType = ErrorType.IRRELEVANT_RESULTS,
                    url = results.firstOrNull()?.url,
                    html = results.take(25).joinToString("\n") { "${it.title}\t${it.url}" },
                )
                log.warn("{}: {} [snapshot:{}]", platformId.displayName, irrelevance, snapId)
                // An answer carrying not one word of the search is about something else, and there
                // is nothing in it to filter. An answer that merely matched badly is filtered
                // listing by listing like any other, and keeps whatever did match.
                if (ignoredSearch != null) return emptyList()
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
            url = e.url, html = e.html, finalUrl = e.url, statusCode = e.statusCode,
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

/**
 * A crawl that reached the market and was turned away.
 *
 * Carries the page it was turned away with. Without it a snapshot records only the sentence the
 * detector printed, which says a block happened and nothing about what the market actually served
 * — and telling a real block from a changed layout needs the page, not the verdict.
 */
class CrawlerBlockedException(
    message: String,
    val errorType: ErrorType,
    val url: String? = null,
    val html: String? = null,
    val statusCode: Int? = null,
) : RuntimeException(message)
