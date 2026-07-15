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
    // Per-filter "would-add if relaxed" counts for this platform's candidates; summed by the app.
    val facetRemoved: Map<String, Int> = emptyMap(),
)

@Serializable
enum class CrawlerEventType {
    SEARCH_STARTED,
    PLATFORM_STARTED,
    PLATFORM_PROGRESS,
    PLATFORM_DONE,
    PLATFORM_ERROR,
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
