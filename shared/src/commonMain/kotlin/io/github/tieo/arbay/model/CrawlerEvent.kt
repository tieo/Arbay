package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class CrawlerSearchEvent(
    val type: CrawlerEventType,
    val platform: String,
    val platformName: String = "",
    val resultCount: Int = 0,
    val rawCount: Int = 0,
    val listings: List<Listing> = emptyList(),
    val error: String? = null,
    val errorType: String? = null,
    val captchaUrl: String? = null,
    val fetchStage: String? = null,
    val totalPlatforms: Int = 0,
    val completedPlatforms: Int = 0,
    val hasMore: Boolean = false,
    val nextPage: Int = 0,
    val fromCache: Boolean = false,
    // Per active car filter, how many more results this platform would yield if that filter were
    // removed (computed locally over the fetched candidate set — the "−N" a chip is hiding).
    // Keyed by filter dimension; summed across platforms by the client.
    val facets: Map<String, Int> = emptyMap(),
)

@Serializable
enum class CrawlerEventType {
    SEARCH_STARTED,
    PLATFORM_STARTED,
    PLATFORM_PROGRESS,
    PLATFORM_DONE,
    PLATFORM_ERROR,
    // A crawl has exposed its live browser (noVNC) for the user to solve a captcha in place; the
    // captchaUrl carries the path to open. The crawl continues once solved.
    CAPTCHA_INTERACTIVE,
    SEARCH_COMPLETE,
}

@Serializable
enum class PlatformSearchStatus {
    PENDING,
    SEARCHING,
    DONE,
    ERROR,
    BLOCKED,
    CAPTCHA,
    TIMEOUT,
    IP_BLOCKED,
}
