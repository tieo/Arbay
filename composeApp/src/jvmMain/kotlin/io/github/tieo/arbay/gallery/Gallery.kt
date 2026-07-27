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
import io.github.tieo.arbay.sample.PreviewData
import java.io.File

/** A gallery of the app's result-view building blocks rendered with sample data, so the design can
 *  be reviewed as a set of PNGs and iterated against the UI rules. Each view is rendered light+dark. */
private val TABLET_W = 1100
private val TABLET_H = 1400
/** One picture to draw: its name, its size in dp, and how many pixels per dp. */
private data class Render(
    val suffix: String,
    val width: Int,
    val height: Int,
    val dark: Boolean,
    val scale: Float,
)

private val CARD_H = 585
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
            productViewModel = io.github.tieo.arbay.ui.viewmodel.ProductViewModel(saved = PreviewData.saved, savedStatus = PreviewData.savedStatus),
            listingViewModel = io.github.tieo.arbay.ui.viewmodel.ListingViewModel(),
            freeItemViewModel = io.github.tieo.arbay.ui.viewmodel.FreeItemViewModel(
                sampleProfile = PreviewData.freeItemProfile,
            ),
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
                sample = PreviewData.active + PreviewData.sold,
                sampleStatuses = PreviewData.marketAnswers,
            ),
            platforms = PreviewData.active.map { it.platformId }.distinct(),
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
            markets = PreviewData.active.groupBy { it.platformId }.map { (platform, items) ->
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
            statuses = PreviewData.marketAnswers,
            offers = PreviewData.active.groupBy { it.platformId }.mapValues { it.value.size },
            capabilities = PreviewData.marketAbilities,
            onSelectMarket = {}, onDismiss = {},
        )
    },
    "price" to inline {
        val newer = PreviewData.active.filter { it.condition?.name == "NEW" }
        val used = PreviewData.active.filter { it.condition?.name != "NEW" }
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
            soldListings = PreviewData.sold,
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
    // Which views to draw, and which sizes: a change to one screen does not need
    // the other eight redrawn, and the model shows light and wide, so dark is
    // drawn only when asked for. Speed is the point of this task.
    val only = System.getProperty("gallery.only")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    val sizes = (System.getProperty("gallery.sizes") ?: "light,wide,card").split(",").map { it.trim() }.toSet()
    val started = System.currentTimeMillis()

    val wanted = VIEWS.filter { (name, _) -> only == null || name in only }
    if (wanted.isEmpty()) {
        println("no view matches ${only?.joinToString(",")}; known: ${VIEWS.joinToString(",") { it.first }}")
        return
    }

    var drawn = 0
    for ((name, view) in wanted) {
        val jobs = buildList {
            if ("light" in sizes) add(Render("light", PHONE_W, PHONE_H, dark = false, scale = 2f))
            if ("dark" in sizes) add(Render("dark", PHONE_W, PHONE_H, dark = true, scale = 2f))
            if ("wide" in sizes) add(Render("wide", TABLET_W, TABLET_H, dark = false, scale = 1f))
            if ("card" in sizes) add(Render("card", PHONE_W, CARD_H, dark = false, scale = 2f))
        }
        for (job in jobs) {
            runCatching {
                renderToPng(
                    "$name-${job.suffix}", job.width, job.height,
                    dark = job.dark, outDir = outDir, scale = job.scale, content = view,
                )
                drawn++
            }.onFailure { println("FAILED $name-${job.suffix}: ${it.message}") }
        }
    }
    println("$drawn renders in ${(System.currentTimeMillis() - started) / 1000.0}s")
    println("gallery written to ${outDir.absolutePath}")
}
