package io.github.tieo.arbay.debug

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SortMode
import io.github.tieo.arbay.ui.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.PlatformStatus
import io.github.tieo.arbay.ui.viewmodel.ProductViewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Used for every debug snapshot, so the dump is a JSON object a person can read, not a wall of
 *  keys on one line. */
internal val debugJson = Json { prettyPrint = true; encodeDefaults = true }

/**
 * Everything [ListingViewModel] holds: what was asked, what every market answered, what the
 * app's own filters narrowed away, and the listings themselves. This is the whole reason the
 * debug dump exists — "why does the screen show this" is usually a question about which of these
 * numbers disagree.
 */
@Serializable
private data class ListingDebugSnapshot(
    val searchQuery: String,
    val loading: Boolean,
    val error: String?,
    val notSearched: String?,
    val totalPlatforms: Int,
    val completedPlatforms: Int,
    val platformStatuses: List<PlatformStatus>,
    val shownMarkets: Set<PlatformId>,
    val shownCountries: Set<String>,
    val blockedTerms: List<String>,
    val bannedIds: Set<String>,
    val sortMode: SortMode,
    val facets: Map<String, Int>,
    // Counts at each stage of the filter pipeline, so a shrinking list can be blamed on the right
    // stage instead of guessed at: fetched -> marketBasis (banned + blocked words removed) ->
    // listings (market/country picks applied) is what the screen actually shows.
    val fetchedCount: Int,
    val marketBasisCount: Int,
    val shownListingsCount: Int,
    val listings: List<Listing>,
)

fun ListingViewModel.debugSnapshotJson(): String = debugJson.encodeToString(
    ListingDebugSnapshot(
        searchQuery = searchQuery.value,
        loading = loading.value,
        error = error.value,
        notSearched = notSearched.value,
        totalPlatforms = totalPlatforms.value,
        completedPlatforms = completedPlatforms.value,
        platformStatuses = platformStatuses.value,
        shownMarkets = shownMarkets.value,
        shownCountries = shownCountries.value,
        blockedTerms = blockedTerms.value,
        bannedIds = bannedIds.value,
        sortMode = sortMode.value,
        facets = facets.value,
        fetchedCount = fetched.value.size,
        marketBasisCount = marketBasis.value.size,
        shownListingsCount = listings.value.size,
        listings = listings.value,
    ),
)

@Serializable
private data class ProductDebugSnapshot(
    val loading: Boolean,
    val error: String?,
    val products: List<io.github.tieo.arbay.model.TrackedProduct>,
    val status: Map<String, io.github.tieo.arbay.model.SavedSearchStatus>,
)

fun ProductViewModel.debugSnapshotJson(): String = debugJson.encodeToString(
    ProductDebugSnapshot(
        loading = loading.value,
        error = error.value,
        products = products.value,
        status = status.value,
    ),
)

@Serializable
private data class FreeItemDebugSnapshot(
    val loading: Boolean,
    val loadingMore: Boolean,
    val historyLoading: Boolean,
    val error: String?,
    val currentRadiusKm: Int?,
    val profile: io.github.tieo.arbay.model.FreeItemProfile?,
    val insights: io.github.tieo.arbay.model.FreeItemInsights?,
    val stats: io.github.tieo.arbay.model.FreeItemStats?,
    val dismissedIds: Set<String>,
    val platformStatus: PlatformStatus?,
    val listingsCount: Int,
    val listings: List<Listing>,
    val newMatches: List<io.github.tieo.arbay.model.NewMatch>,
    val rejectedItems: List<io.github.tieo.arbay.model.RejectedItem>,
    val history: List<io.github.tieo.arbay.model.FeedbackHistoryItem>,
    // Bounded to the last 500 by the view model itself; kept whole here rather than trimmed
    // again, since 500 short events is a few tens of KB at most.
    val telemetry: List<io.github.tieo.arbay.ui.viewmodel.TelemetryEvent>,
)

fun FreeItemViewModel.debugSnapshotJson(): String = debugJson.encodeToString(
    FreeItemDebugSnapshot(
        loading = loading.value,
        loadingMore = loadingMore.value,
        historyLoading = historyLoading.value,
        error = error.value,
        currentRadiusKm = currentRadiusKm.value,
        profile = profile.value,
        insights = insights.value,
        stats = stats.value,
        dismissedIds = dismissedIds.value,
        platformStatus = platformStatus.value,
        listingsCount = listings.value.size,
        listings = listings.value,
        newMatches = newMatches.value,
        rejectedItems = rejectedItems.value,
        history = history.value,
        telemetry = telemetry.value,
    ),
)
