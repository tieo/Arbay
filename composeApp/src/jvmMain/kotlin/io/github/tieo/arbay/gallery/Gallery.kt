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
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.ui.screen.*
import io.github.tieo.arbay.ui.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.ProductViewModel
import io.github.tieo.arbay.sample.PreviewData
import java.io.File

/** A gallery of the app's result-view building blocks rendered with sample data, so the design can
 *  be reviewed as a set of PNGs and iterated against the UI rules. Each view is rendered light+dark. */
// The wide render is looked at beside the upright one, in half a window, so what
// matters is not how many pixels it has but how few logical ones: text keeps its
// size relative to the frame only if the frame is narrow. Density buys sharpness,
// not legibility, since both are scaled to the same width in the end.
private val TABLET_W = 860
private val TABLET_H = 1150
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

/** A screen in one of the states it can be in. The state's name becomes part of the file name, so
 *  the model reads as a list rather than a lookup table. */
private data class Scene(
    val view: String,
    val state: String,
    // How much of the screen to draw. A view whose states differ below a chart needs
    // more of itself in the frame than one whose states differ at the top.
    val tall: Boolean = false,
    val content: @Composable () -> Unit,
)

private fun scene(view: String, state: String, tall: Boolean = false, content: @Composable () -> Unit) =
    Scene(view, state, tall, content)

private val money = { cents: Long -> Money(cents, Currency.EUR) }

private val SCENES: List<Scene> = buildList {
    // ── Home ──────────────────────────────────────────────────────────────────
    add(scene("home", "as-it-is") {
        Home(saved = PreviewData.saved, status = PreviewData.savedStatus, profile = PreviewData.freeItemProfile)
    })
    add(scene("home", "loading") { Home(loading = true) })
    add(scene("home", "empty") { Home() })
    add(scene("home", "failed") { Home(error = "Could not reach the server at localhost:8090") })

    // ── Search ────────────────────────────────────────────────────────────────
    add(scene("search", "as-it-is") { Search() })
    add(scene("search", "no-history") { Search(history = emptyList()) })
    add(scene("search", "every-country") { Search(countries = emptyList()) })

    // ── Results ───────────────────────────────────────────────────────────────
    add(scene("results", "as-it-is") { Results(PreviewData.active + PreviewData.sold, PreviewData.marketAnswers) })
    add(scene("results", "loading") {
        Results(emptyList(), PreviewData.stillAsking, loading = true, total = 6, completed = 4)
    })
    add(scene("results", "with-a-backlog") {
        Results(PreviewData.active, PreviewData.marketAnswers, newIds = PreviewData.newListingIds)
    })
    add(scene("results", "what-the-watch-found") {
        val found = PreviewData.active.filter { it.id in PreviewData.newListingIds }
        Results(found, emptyList(), stored = found)
    })
    add(scene("results", "an-auction-among-the-prices") {
        Results(PreviewData.withAuction, PreviewData.marketAnswers)
    })
    add(scene("results", "empty") { Results(emptyList(), PreviewData.nobodyHadAnything) })
    add(scene("results", "failed") { Results(emptyList(), PreviewData.everyoneFailed) })
    add(scene("results", "some-markets-failed") {
        Results(PreviewData.active.take(4), PreviewData.marketAnswers)
    })
    add(scene("results", "a-market-offers-its-captcha") {
        Results(PreviewData.active.take(3), PreviewData.captchaHeld)
    })
    add(scene("results", "nearest-first-with-nowhere-to-measure-from") {
        Results(PreviewData.active, PreviewData.marketAnswers, sort = io.github.tieo.arbay.model.SortMode.NEAREST)
    })
    add(scene("results", "edit-a-vehicle-search-term") {
        Results(PreviewData.active, PreviewData.marketAnswers, openEditor = true, car = true)
    })
    add(scene("results", "other-words-to-add") {
        Results(
            PreviewData.active, PreviewData.marketAnswers,
            otherWords = PreviewData.otherWords, picked = listOf("parkettschleifer"),
        )
    })
    add(scene("other-words", "every-word-the-markets-printed") {
        io.github.tieo.arbay.ui.screen.OtherWordsSheet(
            words = PreviewData.otherWords,
            picked = listOf("parkettschleifer"),
            searchQuery = "parkettschleifmaschine",
            onToggle = {},
            onDismiss = {},
        )
    })
    add(scene("removed", "what-the-search-took-out") {
        io.github.tieo.arbay.ui.screen.DroppedSheet(
            dropped = PreviewData.droppedBySearch,
            searchQuery = "parkettschleifmaschine",
            onDismiss = {},
        )
    })
    add(scene("results", "what-the-search-removed") {
        Results(PreviewData.active, PreviewData.marketAnswers, dropped = PreviewData.droppedBySearch)
    })
    add(scene("results", "the-filters-admit-none") {
        Results(PreviewData.active, PreviewData.allAnswered, blocked = PreviewData.active.map { it.title })
    })

    // ── Filters ───────────────────────────────────────────────────────────────
    add(scene("filters", "as-it-is") { Filters() })
    add(scene("filters", "nothing-to-narrow") {
        Filters(markets = emptyList(), blocked = emptyList(), active = 0, priceMax = 120f)
    })

    // ── Markets ───────────────────────────────────────────────────────────────
    add(scene("markets", "as-it-is") { Markets(PreviewData.marketAnswers) })
    add(scene("markets", "loading") { Markets(PreviewData.stillAsking) })
    add(scene("markets", "asked-in-their-own-language", tall = true) {
        Markets(
            PreviewData.marketAnswers,
            reach = io.github.tieo.arbay.model.SearchReach(
                otherLanguages = true,
                termByLanguage = mapOf("it" to "levigatrice per parquet"),
            ),
            suggestions = io.github.tieo.arbay.model.TermSuggestions(
                text = "parkettschleifmaschine",
                suggestions = mapOf(
                    "it" to "levigatrice per parquet",
                    "nl" to "parketschuurmachine",
                    "es" to "lijadora de parquet",
                ),
                unavailable = listOf("fr"),
            ),
        )
    })
    add(scene("markets", "empty") { Markets(PreviewData.nobodyHadAnything) })
    add(scene("markets", "failed") { Markets(PreviewData.everyoneFailed) })
    add(scene("markets", "cooling-down") { Markets(PreviewData.everyoneFailed.take(3)) })
    // Several markets picked at once, which is what the picking is for: every other market has to
    // stay on the list, or a second one could never be picked.
    add(scene("markets", "several-picked", tall = true) {
        Markets(
            PreviewData.marketAnswers,
            shownMarkets = setOf(
                io.github.tieo.arbay.model.PlatformId.KLEINANZEIGEN,
                io.github.tieo.arbay.model.PlatformId.RICARDO,
            ),
            shownCountries = setOf("AT"),
        )
    })

    // ── Price ─────────────────────────────────────────────────────────────────
    add(scene("price", "as-it-is", tall = true) { Price() })
    add(scene("price", "loading", tall = true) { Price(soldLoading = true, sold = emptyList()) })
    add(scene("price", "empty", tall = true) { Price(sold = emptyList(), soldPossible = true) })
    add(scene("price", "failed", tall = true) { Price(sold = emptyList(), soldPossible = false) })

    // ── Vehicle search ────────────────────────────────────────────────────────
    add(scene("car-search", "as-it-is") { VehicleSearch() })

    // ── Free items ────────────────────────────────────────────────────────────
    add(scene("free-items", "as-it-is") { FreeItems(profile = PreviewData.freeItemProfile, items = PreviewData.active.take(3)) })
    add(scene("free-items", "loading") { FreeItems(profile = PreviewData.freeItemProfile, loading = true) })
    add(scene("free-items", "empty") { FreeItems(profile = PreviewData.freeItemProfile) })
    add(scene("free-items", "failed") {
        FreeItems(profile = PreviewData.freeItemProfile, error = "Could not reach the server")
    })

    // ── Settings ──────────────────────────────────────────────────────────────
    add(scene("settings", "as-it-is") { Settings() })
}

// ── The screens, each taking the state it is being drawn in ──────────────────

@Composable
private fun Home(
    saved: List<io.github.tieo.arbay.model.TrackedProduct> = emptyList(),
    status: List<io.github.tieo.arbay.model.SavedSearchStatus> = emptyList(),
    profile: io.github.tieo.arbay.model.FreeItemProfile? = null,
    loading: Boolean = false,
    error: String? = null,
) = inline {
    io.github.tieo.arbay.ui.screen.MainScreen(
        productViewModel = ProductViewModel(
            saved = saved, savedStatus = status, sampleLoading = loading, sampleError = error,
            rendersASample = true,
        ),
        listingViewModel = ListingViewModel(),
        freeItemViewModel = FreeItemViewModel(sampleProfile = profile),
        client = ArbayClient(),
    )
}()

@Composable
private fun Search(
    history: List<io.github.tieo.arbay.history.SearchHistoryEntry> = PreviewData.searchHistory,
    countries: List<String> = listOf("DE", "AT", "CH"),
) = inline {
    io.github.tieo.arbay.ui.screen.DiscoverySheet(
        onDismiss = {}, onProductSelected = {}, onCustomSearch = {},
        onLiveSearch = {}, onFreeItems = {}, onCarSearch = {},
        history = history,
        countries = countries,
    )
}()

@Composable
private fun Results(
    listings: List<Listing>,
    statuses: List<io.github.tieo.arbay.ui.viewmodel.PlatformStatus>,
    loading: Boolean = false,
    total: Int = 0,
    completed: Int = 0,
    blocked: List<String> = emptyList(),
    newIds: Set<String> = emptySet(),
    stored: List<Listing>? = null,
    dropped: List<io.github.tieo.arbay.model.DroppedListing> = emptyList(),
    otherWords: List<io.github.tieo.arbay.model.SuggestedTerm> = emptyList(),
    picked: List<String> = emptyList(),
    sort: io.github.tieo.arbay.model.SortMode? = null,
    openEditor: Boolean = false,
    car: Boolean = false,
) = inline {
    io.github.tieo.arbay.ui.screen.ListingsSheet(
        productName = "Parkettschleifmaschine",
        searchQuery = "parkettschleifmaschine",
        listingViewModel = ListingViewModel(
            sample = listings,
            sampleStatuses = statuses,
            sampleLoading = loading,
            sampleTotal = total,
            sampleCompleted = completed,
            sampleBlocked = blocked,
            sampleDropped = dropped,
            sampleOtherWords = otherWords,
            samplePicked = picked,
        ),
        platforms = PreviewData.active.map { it.platformId }.distinct(),
        savedFilters = sort?.let {
            io.github.tieo.arbay.model.SearchQuery(
                text = "parkettschleifmaschine", category = io.github.tieo.arbay.model.MarketGroup.GENERAL, sort = it,
            )
        },
        blockedTerms = blocked,
        newListingIds = newIds,
        storedListings = stored,
        isBookmarked = true,
        onToggleBookmark = {},
        onEditQuery = if (openEditor) ({ _: String -> }) else null,
        onEditFilters = if (car) ({ }) else null,
        openTermEditor = openEditor,
        onDismiss = {},
    )
}()

@Composable
private fun Filters(
    markets: List<io.github.tieo.arbay.ui.screen.MarketChoice> = PreviewData.marketChoices,
    blocked: List<String> = listOf("defekt", "bastler"),
    active: Int = 3,
    priceMax: Float = 1400f,
    shownMarkets: Set<io.github.tieo.arbay.model.PlatformId> = emptySet(),
    shownCountries: Set<String> = emptySet(),
) = inline {
    io.github.tieo.arbay.ui.screen.FiltersSheet(
        priceMin = 120f, priceMax = priceMax, priceRange = 200f..900f,
        onPriceRange = {}, onPriceCommitted = {},
        condition = if (active > 0) "USED" else null, onCondition = {},
        newCount = if (markets.isEmpty()) 0 else 3, usedCount = if (markets.isEmpty()) 0 else 9,
        sort = io.github.tieo.arbay.model.SortMode.PRICE_ASC, onSort = {},
        markets = markets,
        shownMarkets = shownMarkets, shownCountries = shownCountries, onOpenMarkets = {},
        blockedTerms = blocked, onUnblock = {}, onBlock = {},
        activeCount = active, onClearAll = {},
        hasCarCriteria = false, onEditCarCriteria = null,
        onDismiss = {},
    )
}()

@Composable
private fun Markets(
    statuses: List<io.github.tieo.arbay.ui.viewmodel.PlatformStatus>,
    shownMarkets: Set<io.github.tieo.arbay.model.PlatformId> = emptySet(),
    shownCountries: Set<String> = emptySet(),
    reach: io.github.tieo.arbay.model.SearchReach = io.github.tieo.arbay.model.SearchReach(),
    suggestions: io.github.tieo.arbay.model.TermSuggestions? = null,
) = inline {
    io.github.tieo.arbay.ui.screen.MarketsSheet(
        reach = reach,
        suggestions = suggestions,
        statuses = statuses,
        offers = PreviewData.active.groupBy { it.platformId }.mapValues { it.value.size },
        capabilities = PreviewData.marketAbilities,
        shownMarkets = shownMarkets, onShowMarkets = {},
        shownCountries = shownCountries, onShowCountries = {},
        onDismiss = {},
    )
}()

@Composable
private fun Price(
    sold: List<Listing> = PreviewData.sold,
    soldLoading: Boolean = false,
    soldPossible: Boolean = true,
) = inline {
    val newer = PreviewData.active.filter { it.condition?.name == "NEW" }
    val used = PreviewData.active.filter { it.condition?.name != "NEW" }
    io.github.tieo.arbay.ui.screen.PriceSheet(
        minPrice = money(25000), medianPrice = money(72000), maxPrice = money(120000),
        minNewPrice = money(89800), medianNewPrice = money(95000), newCount = newer.size,
        minUsedPrice = money(25000), medianUsedPrice = money(65000), usedCount = used.size,
        conditionFilter = null, onConditionFilterChange = {},
        newListings = newer, usedListings = used,
        soldListings = sold,
        medianSoldPrice = if (sold.isEmpty()) null else money(61000),
        soldLoading = soldLoading, onSearchSold = {}, soldPossible = soldPossible,
        onDismiss = {},
    )
}()

@Composable
private fun VehicleSearch() = inline {
    io.github.tieo.arbay.ui.screen.CarSearchSheet(onDismiss = {}, onSearch = { _, _, _, _, _, _ -> })
}()

@Composable
private fun FreeItems(
    profile: io.github.tieo.arbay.model.FreeItemProfile? = null,
    items: List<Listing> = emptyList(),
    loading: Boolean = false,
    error: String? = null,
) = inline {
    io.github.tieo.arbay.ui.screen.FreeItemsSheet(
        viewModel = FreeItemViewModel(
            sampleProfile = profile, sampleItems = items, sampleLoading = loading, sampleError = error,
            rendersASample = true,
        ),
        onDismiss = {},
    )
}()

@Composable
private fun Settings() = inline {
    io.github.tieo.arbay.ui.screen.SettingsSheet(client = ArbayClient(), onDismiss = {})
}()

fun main() {
    val outDir = File(System.getProperty("gallery.out") ?: "build/gallery")
    // A change to one screen does not need the other eight redrawn, and the
    // model shows a phone and a wide window, so dark is drawn only when asked.
    val only = System.getProperty("gallery.only")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    val sizes = (System.getProperty("gallery.sizes") ?: "phone,wide,card").split(",").map { it.trim() }.toSet()
    val themes = (System.getProperty("gallery.themes") ?: "light,dark").split(",").map { it.trim() }.toSet()
    val started = System.currentTimeMillis()

    val wanted = SCENES.filter { only == null || it.view in only || "${it.view}-${it.state}" in only }
    if (wanted.isEmpty()) {
        println("no scene matches ${only?.joinToString(",")}; views: ${SCENES.map { it.view }.distinct().joinToString(",")}")
        return
    }

    var drawn = 0
    for (scene in wanted) {
        // The screen as it is keeps the plain name; a state carries its own, so
        // results-empty-phone.png says what it is without a table to look it up in.
        val stem = if (scene.state == "as-it-is") scene.view else "${scene.view}-${scene.state}"
        // Both shapes in both themes, with the theme in the name: a book read in
        // the dark that falls back to whichever render does not say "light" ends
        // up showing some screens light and some dark.
        val jobs = buildList {
            for (theme in listOf("light", "dark")) {
                if (theme !in themes) continue
                val dark = theme == "dark"
                val tall = if (scene.tall) (PHONE_H * 1.7f).toInt() else PHONE_H
                if ("phone" in sizes) add(Render("phone-$theme", PHONE_W, tall, dark, 2f))
                if ("wide" in sizes) {
                    add(Render("wide-$theme", TABLET_W, if (scene.tall) (TABLET_H * 1.5f).toInt() else TABLET_H, dark, 1.5f))
                }
                // Only the screen itself needs a card; the index shows views, not states.
                if ("card" in sizes && scene.state == "as-it-is") {
                    add(Render("card-$theme", PHONE_W, CARD_H, dark, 2f))
                }
            }
        }
        for (job in jobs) {
            runCatching {
                renderToPng(
                    "$stem-${job.suffix}", job.width, job.height,
                    dark = job.dark, outDir = outDir, scale = job.scale, content = scene.content,
                )
                drawn++
            }.onFailure { println("FAILED $stem-${job.suffix}: ${it.message}") }
        }
    }
    println("$drawn renders of ${wanted.size} scenes in ${(System.currentTimeMillis() - started) / 1000.0}s")
    println("gallery written to ${outDir.absolutePath}")
}
