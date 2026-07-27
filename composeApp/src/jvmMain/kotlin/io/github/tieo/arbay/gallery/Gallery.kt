package io.github.tieo.arbay.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.ui.screen.*
import java.io.File

/** A gallery of the app's result-view building blocks rendered with sample data, so the design can
 *  be reviewed as a set of PNGs and iterated against the UI rules. Each view is rendered light+dark. */
private val PHONE_W = 390
private val PHONE_H = 1600

/** Every top-level view rendered inline (the sheets normally wrap in a Dialog, which an off-screen
 *  scene cannot capture; LocalRenderInline makes them paint in place). */
private fun inline(content: @Composable () -> Unit): @Composable () -> Unit = {
    androidx.compose.runtime.CompositionLocalProvider(io.github.tieo.arbay.ui.LocalRenderInline provides true, content = content)
}

private val VIEWS: List<Pair<String, @Composable () -> Unit>> = listOf(
    "home" to {
        io.github.tieo.arbay.ui.screen.MainScreen(
            productViewModel = io.github.tieo.arbay.ui.viewmodel.ProductViewModel(saved = SampleData.saved, savedStatus = SampleData.savedStatus),
            listingViewModel = io.github.tieo.arbay.ui.viewmodel.ListingViewModel(),
            freeItemViewModel = io.github.tieo.arbay.ui.viewmodel.FreeItemViewModel(),
            client = io.github.tieo.arbay.api.ArbayClient(),
        )
    },
    "free-items" to inline {
        io.github.tieo.arbay.ui.screen.FreeItemsSheet(
            viewModel = io.github.tieo.arbay.ui.viewmodel.FreeItemViewModel(), onDismiss = {},
        )
    },
    // The real results view with results handed to it, so the gallery shows the screen the app
    // renders rather than an arrangement of its parts.
    "results" to inline {
        io.github.tieo.arbay.ui.screen.ListingsSheet(
            productName = "Parkettschleifmaschine",
            searchQuery = "parkettschleifmaschine",
            listingViewModel = io.github.tieo.arbay.ui.viewmodel.ListingViewModel(
                sample = SampleData.active + SampleData.sold,
                sampleStatuses = SampleData.marketAnswers,
            ),
            platforms = SampleData.active.map { it.platformId }.distinct(),
            isBookmarked = true,
            onToggleBookmark = {},
            onDismiss = {},
        )
    },
    "filters" to inline {
        io.github.tieo.arbay.ui.screen.FiltersSheet(
            priceMin = 120f, priceMax = 1400f, priceRange = 200f..900f,
            onPriceRange = {}, onPriceCommitted = {},
            condition = "USED", onCondition = {}, newCount = 3, usedCount = 9,
            sort = io.github.tieo.arbay.model.SortMode.PRICE_ASC, onSort = {},
            markets = SampleData.active.groupBy { it.platformId }.map { (platform, items) ->
                io.github.tieo.arbay.ui.screen.MarketChoice(
                    platform = platform, name = platform.displayName,
                    country = io.github.tieo.arbay.model.MarketSets.countryOf(platform),
                    count = items.size,
                )
            },
            shownMarkets = setOf(io.github.tieo.arbay.model.PlatformId.KLEINANZEIGEN), onShowMarkets = {},
            shownCountries = emptySet(), onShowCountries = {},
            blockedTerms = listOf("defekt", "bastler"), onUnblock = {}, onBlock = {},
            activeCount = 3, onClearAll = {},
            hasCarCriteria = false, onEditCarCriteria = null,
            onDismiss = {},
        )
    },
    "markets" to inline {
        io.github.tieo.arbay.ui.screen.MarketsSheet(
            statuses = SampleData.marketAnswers,
            offers = SampleData.active.groupBy { it.platformId }.mapValues { it.value.size },
            onSelectMarket = {}, onDismiss = {},
        )
    },
    "price" to inline {
        val newer = SampleData.active.filter { it.condition?.name == "NEW" }
        val used = SampleData.active.filter { it.condition?.name != "NEW" }
        io.github.tieo.arbay.ui.screen.PriceSheet(
            minPrice = Money(25000, Currency.EUR),
            medianPrice = Money(72000, Currency.EUR),
            maxPrice = Money(120000, Currency.EUR),
            minNewPrice = Money(89800, Currency.EUR),
            medianNewPrice = Money(95000, Currency.EUR),
            newCount = newer.size,
            minUsedPrice = Money(25000, Currency.EUR),
            medianUsedPrice = Money(65000, Currency.EUR),
            usedCount = used.size,
            conditionFilter = null, onConditionFilterChange = {},
            newListings = newer, usedListings = used,
            soldListings = SampleData.sold,
            medianSoldPrice = Money(61000, Currency.EUR),
            soldLoading = false, onSearchSold = {}, soldPossible = true,
            onDismiss = {},
        )
    },
    "search" to {
        androidx.compose.runtime.CompositionLocalProvider(io.github.tieo.arbay.ui.LocalRenderInline provides true) {
            io.github.tieo.arbay.ui.screen.DiscoverySheet(
                onDismiss = {}, onProductSelected = {}, onCustomSearch = {},
                onLiveSearch = {}, onFreeItems = {}, onCarSearch = {},
            )
        }
    },
    "car-search" to {
        androidx.compose.runtime.CompositionLocalProvider(io.github.tieo.arbay.ui.LocalRenderInline provides true) {
            io.github.tieo.arbay.ui.screen.CarSearchSheet(onDismiss = {}, onSearch = { _, _, _, _, _, _ -> })
        }
    },
    "settings" to {
        androidx.compose.runtime.CompositionLocalProvider(io.github.tieo.arbay.ui.LocalRenderInline provides true) {
            io.github.tieo.arbay.ui.screen.SettingsSheet(client = io.github.tieo.arbay.api.ArbayClient(), onDismiss = {})
        }
    },
)

fun main() {
    val outDir = File(System.getProperty("gallery.out") ?: "build/gallery")
    for ((name, view) in VIEWS) {
        for (dark in listOf(false, true)) {
            val suffix = if (dark) "dark" else "light"
            runCatching {
                renderToPng("$name-$suffix", PHONE_W, PHONE_H, dark = dark, outDir = outDir, content = view)
            }.onFailure { println("FAILED $name-$suffix: ${it.message}") }
        }
    }
    println("gallery written to ${outDir.absolutePath}")
}
