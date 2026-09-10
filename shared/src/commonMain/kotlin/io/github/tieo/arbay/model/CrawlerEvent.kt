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
    // What this market sent that the search removed, with the reason for each. Carried so the app
    // can show what it dropped: a market's answer disappearing with no number attached is how a
    // filter gets to be wrong for months without anyone finding out.
    val dropped: List<DroppedListing> = emptyList(),
    val error: String? = null,
    val errorType: String? = null,
    val captchaUrl: String? = null,
    // The term this platform was actually searched with when it differs from the user's query — a
    // cross-border market asked in its own language (e.g. "levigatrice per parquet").
    val queryUsed: String? = null,
    // Every term this market was searched with, in order: the one it was handed, then each other
    // name for the thing the search was allowed to follow up on. What the market was asked is the
    // other half of what the market answered, so it is reported rather than inferred.
    val termsUsed: List<String> = emptyList(),
    // Every other word this market printed under the search, with the app's verdict on each. Shown
    // rather than acted on: the rules that pick between them are right about what they accept and
    // miss most of what they reject, so the reader gets the list and the reasons and decides.
    val suggestedTerms: List<SuggestedTerm> = emptyList(),
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
