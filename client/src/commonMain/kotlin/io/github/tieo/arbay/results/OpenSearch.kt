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
    /** How often each Kleinanzeigen ad has been looked at, where known. */
    val views: Map<String, Int> = emptyMap(),
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
    val views by vm.views.collectAsState()

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
    val scams = remember(fetched, views, money) { likelyScams(fetched, views) }
    val narrowed = remember(listings, current, money, open.newListingIds, scams) { narrow(listings, current, open.newListingIds, scams.keys) }
    val summary = remember(narrowed, priceHistory, money) { priceSummary(narrowed.displayed, priceHistory) }
    val hidden = remember(narrowed, fetched, marketBasis, banned, blocked, dropped, scams) {
        hiddenListings(narrowed, current, fetched, marketBasis, banned, blocked, dropped, open.newListingIds, scams)
    }
    return ResultsState(open, current, narrowed, summary, elsewhere, hidden, markets, { narrowing = it }, views)
}

// ── What the reader can change about an open search ────────────────────────
//
// Each change narrows the screen at once and is written where the search is kept, so the same
// search opens narrowed the same way next time, on any device. None of them asks the markets again.

fun ResultsState.setBand(products: ProductViewModel, band: ClosedFloatingPointRange<Float>) {
    setNarrowing(narrowing.copy(band = band))
    open.persist(products) { it.withBand(band, narrowed) }
}

fun ResultsState.clearBand(products: ProductViewModel) {
    setNarrowing(narrowing.copy(band = null))
    open.persist(products) { it.copy(minPrice = null, maxPrice = null) }
}

fun ResultsState.toggleCondition(products: ProductViewModel, value: io.github.tieo.arbay.model.Condition) {
    val next = if (value in narrowing.conditions) narrowing.conditions - value else narrowing.conditions + value
    setConditions(products, next, narrowing.unstatedCondition)
}

fun ResultsState.toggleUnstatedCondition(products: ProductViewModel) =
    setConditions(products, narrowing.conditions, !narrowing.unstatedCondition)

fun ResultsState.showEveryCondition(products: ProductViewModel) = setConditions(products, emptySet(), true)

private fun ResultsState.setConditions(products: ProductViewModel, next: Set<io.github.tieo.arbay.model.Condition>, unstated: Boolean) {
    setNarrowing(narrowing.copy(conditions = next, unstatedCondition = unstated))
    open.persist(products) { it.copy(condition = next.toList().takeIf { l -> l.isNotEmpty() }, conditionUnstated = unstated) }
}

/** Put a condition out of sight, or bring it back; hiding one that was picked unpicks it. */
fun ResultsState.toggleHiddenCondition(products: ProductViewModel, value: io.github.tieo.arbay.model.Condition) {
    val hidden = if (value in narrowing.hiddenConditions) narrowing.hiddenConditions - value else narrowing.hiddenConditions + value
    val wanted = narrowing.conditions - hidden
    setNarrowing(narrowing.copy(hiddenConditions = hidden, conditions = wanted))
    open.persist(products) { it.copy(hiddenConditions = hidden.toList(), condition = wanted.toList().takeIf { l -> l.isNotEmpty() }) }
}

/** Put a way of being sold out of sight (auctions, say), or bring it back. */
fun ResultsState.toggleHiddenSaleType(products: ProductViewModel, value: io.github.tieo.arbay.model.SaleType) {
    val hidden = if (value in narrowing.hiddenSaleTypes) narrowing.hiddenSaleTypes - value else narrowing.hiddenSaleTypes + value
    val wanted = narrowing.saleTypes - hidden
    setNarrowing(narrowing.copy(hiddenSaleTypes = hidden, saleTypes = wanted))
    open.persist(products) { it.copy(hiddenSaleTypes = hidden.toList(), saleTypes = wanted.toList().takeIf { l -> l.isNotEmpty() }) }
}

fun ResultsState.toggleSaleType(products: ProductViewModel, value: io.github.tieo.arbay.model.SaleType) {
    val next = if (value in narrowing.saleTypes) narrowing.saleTypes - value else narrowing.saleTypes + value
    setNarrowing(narrowing.copy(saleTypes = next))
    open.persist(products) { it.copy(saleTypes = next.toList().takeIf { l -> l.isNotEmpty() }) }
}

fun ResultsState.showBothSaleTypes(products: ProductViewModel) {
    setNarrowing(narrowing.copy(saleTypes = emptySet(), unstatedSaleType = true))
    open.persist(products) { it.copy(saleTypes = null, saleTypeUnstated = true) }
}

/** "New" is about this opening only, so it is never written down. */
fun ResultsState.toggleNewOnly() = setNarrowing(narrowing.copy(newOnly = !narrowing.newOnly))

/** How many of the offers on screen arrived since the search was last opened. */
val ResultsState.newCount: Int get() = narrowed.allActive.count { it.id in open.newListingIds }

fun ResultsState.setSort(products: ProductViewModel, listings: ListingViewModel, mode: io.github.tieo.arbay.model.SortMode) {
    listings.setSortMode(mode)
    open.persist(products) { it.copy(sort = mode) }
}

fun ResultsState.blockWord(products: ProductViewModel, listings: ListingViewModel, word: String) {
    val next = listings.blockTerm(word)
    open.persist(products) { it.copy(excludeKeywords = next) }
}

fun ResultsState.unblockWord(products: ProductViewModel, listings: ListingViewModel, word: String) {
    val next = listings.unblockTerm(word)
    open.persist(products) { it.copy(excludeKeywords = next) }
}

fun ResultsState.setAliases(products: ProductViewModel, aliases: List<String>) =
    open.persist(products) { it.copy(aliases = aliases.distinct()) }

fun ResultsState.setOtherWords(products: ProductViewModel, listings: ListingViewModel, on: Boolean) {
    val next = listings.searchReach.value.copy(otherWords = on)
    listings.setReach(next, markets)
    open.persist(products) { it.copy(reach = next) }
}

fun ResultsState.toggleSuggestedWord(products: ProductViewModel, listings: ListingViewModel, term: String) {
    val on = listings.pickedWords.value.any { it.equals(term, ignoreCase = true) }
    listings.toggleWord(term, markets)
    open.persist(products) { q ->
        val words = if (on) q.reach.extraTerms.filterNot { it.equals(term, ignoreCase = true) } else q.reach.extraTerms + term
        q.copy(reach = q.reach.copy(extraTerms = words))
    }
}

fun ResultsState.toggleMarket(products: ProductViewModel, listings: ListingViewModel, platform: PlatformId) {
    val shown = listings.shownMarkets.value
    val next = if (platform in shown) shown - platform else shown + platform
    listings.showMarkets(next)
    open.persist(products) { it.copy(showOnlyMarkets = next) }
}

/** Saves the search as it stands on screen, or forgets the saved one. */
fun ResultsState.toggleSaved(products: ProductViewModel, listings: ListingViewModel) {
    val bookmark = open.bookmark
    if (bookmark != null) { products.deleteProduct(bookmark.id); return }
    val view = open.view
    products.createProduct(
        view.name.ifBlank { view.query },
        bookmarkQuery(
            onScreen = open.saved ?: SearchHistoryStore.baseQuery(view.query, view.platforms, view.filters, view.category),
            text = view.query, asked = markets, category = view.category, carFilters = view.filters,
            blockedWords = listings.blockedTerms.value, aliases = view.aliases,
        ),
    )
}

/**
 * Rename a saved search and change the words it asks the markets for; new words ask the markets
 * again. A name left empty takes the words.
 */
fun ResultsState.changeSavedSearch(products: ProductViewModel, name: String, term: String) {
    val bookmark = open.bookmark ?: return
    val words = term.trim().ifEmpty { return }
    products.updateProduct(bookmark.copy(name = name.trim().ifEmpty { words }, searchQuery = bookmark.searchQuery.copy(text = words)))
}
