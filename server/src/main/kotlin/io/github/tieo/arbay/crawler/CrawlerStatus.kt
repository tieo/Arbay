package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.PlatformId
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class PlatformStatus(
    val platformId: String,
    val platformName: String,
    val supported: Boolean,
    val lastAttempt: Instant? = null,
    val lastSuccess: Instant? = null,
    val lastResultCount: Int = 0,
    val lastError: String? = null,
    val errorType: ErrorType? = null,
    val totalCrawls: Int = 0,
    val totalSuccesses: Int = 0,
    val totalListingsFound: Int = 0,
)

@Serializable
enum class ErrorType {
    NONE,
    BLOCKED_403,
    CAPTCHA,
    RATE_LIMITED_429,
    AUTH_REQUIRED_401,
    SERVICE_UNAVAILABLE_503,
    // The page is not there (404/410). An answer, not a refusal: another engine gets the same.
    NOT_FOUND_404,
    TIMEOUT,
    PARSE_ERROR,
    NETWORK_ERROR,
    EMPTY_RESULTS,
    IRRELEVANT_RESULTS,
    UNKNOWN,
}

object CrawlerStatusTracker {
    private val statuses = ConcurrentHashMap<PlatformId, MutablePlatformStatus>()

    fun recordAttempt(platformId: PlatformId) {
        getOrCreate(platformId).apply {
            lastAttempt = Clock.System.now()
            totalCrawls++
        }
    }

    fun recordSuccess(platformId: PlatformId, resultCount: Int) {
        getOrCreate(platformId).apply {
            lastSuccess = Clock.System.now()
            lastResultCount = resultCount
            lastError = null
            errorType = ErrorType.NONE
            totalSuccesses++
            totalListingsFound += resultCount
        }
    }

    fun recordError(platformId: PlatformId, error: String, type: ErrorType) {
        getOrCreate(platformId).apply {
            lastError = error
            errorType = type
        }
    }

    fun getAll(): List<PlatformStatus> {
        return PlatformId.entries.map { platformId ->
            val status = statuses[platformId]
            val crawler = CrawlerRegistry.crawlerFor(platformId)
            PlatformStatus(
                platformId = platformId.name,
                platformName = platformId.displayName,
                supported = crawler != null,
                lastAttempt = status?.lastAttempt,
                lastSuccess = status?.lastSuccess,
                lastResultCount = status?.lastResultCount ?: 0,
                lastError = status?.lastError,
                errorType = status?.errorType,
                totalCrawls = status?.totalCrawls ?: 0,
                totalSuccesses = status?.totalSuccesses ?: 0,
                totalListingsFound = status?.totalListingsFound ?: 0,
            )
        }
    }

    fun getStatus(platformId: PlatformId): PlatformStatus {
        val status = statuses[platformId]
        val crawler = CrawlerRegistry.crawlerFor(platformId)
        return PlatformStatus(
            platformId = platformId.name,
            platformName = platformId.displayName,
            supported = crawler != null,
            lastAttempt = status?.lastAttempt,
            lastSuccess = status?.lastSuccess,
            lastResultCount = status?.lastResultCount ?: 0,
            lastError = status?.lastError,
            errorType = status?.errorType,
            totalCrawls = status?.totalCrawls ?: 0,
            totalSuccesses = status?.totalSuccesses ?: 0,
            totalListingsFound = status?.totalListingsFound ?: 0,
        )
    }

    private fun getOrCreate(platformId: PlatformId): MutablePlatformStatus {
        return statuses.getOrPut(platformId) { MutablePlatformStatus() }
    }

    private class MutablePlatformStatus {
        var lastAttempt: Instant? = null
        var lastSuccess: Instant? = null
        var lastResultCount: Int = 0
        var lastError: String? = null
        var errorType: ErrorType? = null
        var totalCrawls: Int = 0
        var totalSuccesses: Int = 0
        var totalListingsFound: Int = 0
    }
}
