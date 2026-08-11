package io.github.tieo.arbay.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.ui.LocalRenderInline
import io.github.tieo.arbay.ui.screen.*
import io.github.tieo.arbay.ui.theme.ArbayTheme
import io.github.tieo.arbay.sample.PreviewData
import io.github.tieo.arbay.ui.viewmodel.*
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Every view as a preview, for the screenshot toolchain to render.
 *
 * The same sample data the off-screen renderer uses, so the two paths can be
 * timed against each other rather than argued about. Sheets normally wrap in a
 * Dialog, which a preview cannot capture, so they paint in place here for the
 * same reason they do there.
 */
private @Composable fun inPlace(content: @Composable () -> Unit) {
    ArbayTheme {
        CompositionLocalProvider(LocalRenderInline provides true, content = content)
    }
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
fun HomePreview() = inPlace {
    MainScreen(
        productViewModel = ProductViewModel(saved = PreviewData.saved, savedStatus = PreviewData.savedStatus),
        listingViewModel = ListingViewModel(),
        freeItemViewModel = FreeItemViewModel(sampleProfile = PreviewData.freeItemProfile),
        client = ArbayClient(),
    )
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
fun ResultsPreview() = inPlace {
    ListingsSheet(
        productName = "Parkettschleifmaschine",
        searchQuery = "parkettschleifmaschine",
        listingViewModel = ListingViewModel(
            sample = PreviewData.active + PreviewData.sold,
            sampleStatuses = PreviewData.marketAnswers,
        ),
        platforms = PreviewData.active.map { it.platformId }.distinct(),
        isBookmarked = true,
        onToggleBookmark = {},
        onDismiss = {},
    )
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
fun FiltersPreview() = inPlace {
    FiltersSheet(
        priceMin = 120f, priceMax = 1400f, priceRange = 200f..900f,
        onPriceRange = {}, onPriceCommitted = {},
        condition = "USED", onCondition = {}, newCount = 3, usedCount = 9,
        sort = io.github.tieo.arbay.model.SortMode.PRICE_ASC, onSort = {},
        markets = PreviewData.marketChoices,
        shownMarkets = emptySet(), shownCountries = emptySet(), onOpenMarkets = {},
        blockedTerms = listOf("defekt", "bastler"), onUnblock = {}, onBlock = {},
        activeCount = 3, onClearAll = {},
        hasCarCriteria = false, onEditCarCriteria = null,
        onDismiss = {},
    )
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
fun MarketsPreview() = inPlace {
    MarketsSheet(
        statuses = PreviewData.marketAnswers,
        offers = PreviewData.active.groupBy { it.platformId }.mapValues { it.value.size },
        capabilities = PreviewData.marketAbilities,
        onDismiss = {},
    )
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
fun SearchPreview() = inPlace {
    DiscoverySheet(
        onDismiss = {}, onProductSelected = {}, onCustomSearch = {},
        onLiveSearch = {}, onFreeItems = {}, onCarSearch = {},
    )
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
fun VehicleSearchPreview() = inPlace {
    CarSearchSheet(onDismiss = {}, onSearch = { _, _, _, _, _, _ -> })
}

@Preview(widthDp = 390, heightDp = 844)
@Composable
fun SettingsPreview() = inPlace {
    SettingsSheet(client = ArbayClient(), onDismiss = {})
}
