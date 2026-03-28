package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import org.slf4j.LoggerFactory

interface Crawler {
    val platformId: PlatformId

    suspend fun search(query: SearchQuery): List<Listing>
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
            CrawlerStatusTracker.recordSuccess(platformId, results.size)
            log.info("{}: {} results for '{}'", platformId.displayName, results.size, query.text)
        }
        results
    } catch (e: CrawlerBlockedException) {
        CrawlerStatusTracker.recordError(platformId, e.message ?: "Blocked", e.errorType)
        val snapId = ErrorSnapshotStore.capture(
            platform = platformId.name, query = query.text, error = e, errorType = e.errorType,
        )
        log.warn("{}: blocked — {} [snapshot:{}]", platformId.displayName, e.message, snapId)
        emptyList()
    } catch (e: Exception) {
        val errorType = classifyException(e)
        CrawlerStatusTracker.recordError(platformId, e.message ?: "Unknown error", errorType)
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
