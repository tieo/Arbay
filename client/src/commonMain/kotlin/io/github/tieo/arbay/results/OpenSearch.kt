package io.github.tieo.arbay.results

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.DevicePosition
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.ImportRules
import io.github.tieo.arbay.history.SearchHistoryStore
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SearchReach
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.navigation.Source
import io.github.tieo.arbay.viewmodel.ListingViewModel
import io.github.tieo.arbay.viewmodel.ProductViewModel

/**
 * One open search: what it asks, the bookmark behind it if it is saved, where its narrowing is kept
 * (on the bookmark, else in history), and what it found before it was opened.
 */
class OpenSearch(
    val view: ResultsView,
    val bookmark: TrackedProduct?,
    val saved: SearchQuery?,
    val newListingIds: Set<String>,
) {
    /** Writes a change to this search where it is kept. Never starts a crawl. */
    fun persist(products: ProductViewModel, edit: (SearchQuery) -> SearchQuery) {
        val base = saved ?: SearchHistoryStore.baseQuery(view.query, view.platforms, view.filters, view.category)
        val next = edit(base)
        if (bookmark != null) products.updateProduct(bookmark.copy(searchQuery = next))
        else SearchHistoryStore.record(view.name, next)
    }
}

/**
 * The search a [source] names, or null while a saved one is still loading or once it is gone.
 *
 * Opening a saved search is what clears what it found since it was last opened, so that backlog is
 * read once, as it opens, and kept for as long as the search stays open.
 */
@Composable
fun rememberOpenSearch(source: Source, products: ProductViewModel): OpenSearch? {
    val saved by products.products.collectAsState()
    val status by products.status.collectAsState()
    val history by SearchHistoryStore.entries.collectAsState()

    var backlog by remember(source) { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(source, status.isNotEmpty()) {
        if (source is Source.Saved && backlog == null && status.isNotEmpty()) {
            backlog = status[source.id]?.newListingIds.orEmpty().toSet()
            products.markOpened(source.id)
        }
        if (source is Source.Typed) SearchHistoryStore.recordOpen(source.text, source.text, null, null, MarketGroup.GENERAL)
    }

    fun bookmarkFor(text: String, category: MarketGroup) =
        saved.firstOrNull { it.searchQuery.category == category && it.searchQuery.text.equals(text, ignoreCase = true) }
    fun entryFor(text: String, category: MarketGroup) =
        history.firstOrNull { it.searchQuery.category == category && it.searchQuery.text.equals(text, ignoreCase = true) }

    return when (source) {
        is Source.Saved -> saved.firstOrNull { it.id == source.id }?.let {
            OpenSearch(ResultsView.of(it), it, it.searchQuery, backlog.orEmpty())
        }
        is Source.Typed -> {
            val bookmark = bookmarkFor(source.text, MarketGroup.GENERAL)
            val entry = entryFor(source.text, MarketGroup.GENERAL)
            OpenSearch(ResultsView.of(source.text, source.text, null, MarketGroup.GENERAL), bookmark, bookmark?.searchQuery ?: entry?.searchQuery, emptySet())
        }
        is Source.Vehicle -> {
            val bookmark = bookmarkFor(source.text, MarketGroup.VEHICLES)
            val entry = entryFor(source.text, MarketGroup.VEHICLES)
            when {
                bookmark != null -> OpenSearch(ResultsView.of(bookmark), bookmark, bookmark.searchQuery, emptySet())
                entry != null -> OpenSearch(ResultsView.of(entry), null, entry.searchQuery, emptySet())
                else -> null
            }
        }
    }
}

/** Everything a results screen and its panels read, worked out once per change. */
class ResultsState(
    val open: OpenSearch,
    val narrowing: Narrowing,
    val narrowed: Narrowed,
    val summary: PriceSummary,
    val elsewhere: Map<String, List<Listing>>,
    val hidden: List<Hidden>,
    val markets: List<PlatformId>,
    val setNarrowing: (Narrowing) -> Unit,
) {
    /** The offers on screen, in order. */
    val shown: List<Listing> get() = narrowed.displayed
}

/**
 * Runs an open search and keeps its picture current: the search's own settings go into [vm] as it
 * opens, the markets are asked again only when what defines the crawl changes (compared by value),
 * and the reader's narrowing is read out of the search once and is theirs from then on.
 */
@Composable
fun rememberResultsState(vm: ListingViewModel, open: OpenSearch): ResultsState {
    val view = open.view
    val listings by vm.listings.collectAsState()
    val elsewhere by vm.elsewhere.collectAsState()
    val fetched by vm.fetched.collectAsState()
    val shownMarkets by vm.shownMarkets.collectAsState()
    val priceHistory by vm.priceHistory.collectAsState()
    val banned by vm.bannedIds.collectAsState()
    val blocked by vm.blockedTerms.collectAsState()
    val marketBasis by vm.marketBasis.collectAsState()
    val dropped by vm.droppedBySearch.collectAsState()

    val savedBlocked = open.saved?.excludeKeywords.orEmpty()
    LaunchedEffect(view.query, view.category) {
        vm.setBlockedTerms(savedBlocked)
        open.saved?.let {
            vm.showMarkets(it.showOnlyMarkets)
            vm.showCountries(it.showOnlyCountries)
            it.sort?.let(vm::setSortMode)
        }
    }
    LaunchedEffect(DevicePosition.latitude, DevicePosition.longitude) {
        val lat = DevicePosition.latitude
        val lon = DevicePosition.longitude
        if (lat != null && lon != null) vm.setLocation(lat, lon)
    }

    val markets = remember(view.platforms, view.isCar, shownMarkets) { marketsToAsk(view.platforms, view.isCar, shownMarkets) }
    val crawlKey = listOf(view.query, view.filters, view.aliases, markets.map { it.name }, open.saved?.location, open.saved?.radiusKm, open.saved?.reach).toString()
    LaunchedEffect(crawlKey) {
        vm.search(
            view.query, markets, view.filters, excludeKeywords = savedBlocked, aliases = view.aliases,
            reach = open.saved?.reach ?: SearchReach(),
            near = open.saved?.location,
            radiusKm = open.saved?.radiusKm?.takeIf { it > 0 },
        )
    }

    var narrowing by remember(view.name, view.query, view.category) { mutableStateOf<Narrowing?>(null) }
    if (narrowing == null) narrowing = Narrowing.of(open.saved)
    val current = narrowing ?: Narrowing()
    val money = Triple(DisplayCurrency.current, DisplayCurrency.rates, ImportRules.current)
    val narrowed = remember(listings, current, money, open.newListingIds) { narrow(listings, current, open.newListingIds) }
    val summary = remember(narrowed, priceHistory, money) { priceSummary(narrowed.displayed, priceHistory) }
    val hidden = remember(narrowed, fetched, marketBasis, banned, blocked, dropped) {
        hiddenListings(narrowed, current, fetched, marketBasis, banned, blocked, dropped, open.newListingIds)
    }
    return ResultsState(open, current, narrowed, summary, elsewhere, hidden, markets) { narrowing = it }
}
