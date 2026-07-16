package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.VehicleInfo
import org.slf4j.LoggerFactory

interface Crawler {
    val platformId: PlatformId

    suspend fun search(query: SearchQuery): List<Listing>

    /** Fetch a single listing's detail page and return its verified vehicle specs, where the
     *  detail page carries structured attributes the search card omits (power, gearbox, doors,
     *  emission, colour…). Default null: the platform's card already holds everything it knows. */
    suspend fun fetchDetailVehicle(listing: Listing): VehicleInfo? = null
}

suspend fun Crawler.trackedSearch(query: SearchQuery): List<Listing> {
    val log = LoggerFactory.getLogger("Crawler[${platformId.displayName}]")
    CrawlerStatusTracker.recordAttempt(platformId)
    return try {
        val results = search(query)
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
        val errorType = classifyException(e)
        // Skip snapshots for cancellation-like errors
        val isCancellation = e.message?.contains("Cancelling") == true || e.message?.contains("Cancelled") == true
        CrawlerStatusTracker.recordError(platformId, e.message ?: "Unknown error", errorType)
        if (BlockCooldown.isBlock(errorType)) BlockCooldown.record(platformId)
        if (!isCancellation) {
            val snapId = ErrorSnapshotStore.capture(
                platform = platformId.name, query = query.text, error = e, errorType = errorType,
            )
            log.error("{}: error — {} [snapshot:{}]", platformId.displayName, e.message, snapId)
        } else {
            log.debug("{}: cancelled — {}", platformId.displayName, e.message)
        }
        emptyList()
    }
}

class CrawlerBlockedException(
    message: String,
    val errorType: ErrorType,
) : RuntimeException(message)
