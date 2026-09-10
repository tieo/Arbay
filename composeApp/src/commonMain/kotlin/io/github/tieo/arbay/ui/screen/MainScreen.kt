package io.github.tieo.arbay.ui.screen

import io.github.tieo.arbay.SearchCountries
import io.github.tieo.arbay.model.MarketSettings
import io.github.tieo.arbay.model.platformsIn
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCategory
import io.github.tieo.arbay.debug.DebugRegistry
import io.github.tieo.arbay.debug.debugJson
import io.github.tieo.arbay.history.SearchHistoryEntry
import io.github.tieo.arbay.history.SearchHistoryStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import io.github.tieo.arbay.model.FreeItemProfile
import io.github.tieo.arbay.model.FreeItemStats
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.PlatformId
import kotlinx.coroutines.launch
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SavedSearchStatus
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.ProductIdentifier
import io.github.tieo.arbay.CarTaxonomyStore
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.CarMakeNode
import io.github.tieo.arbay.model.CarModelNode
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.model.toCarFilters
import io.github.tieo.arbay.model.withCarFilters
import io.github.tieo.arbay.model.withPriceRangeEur
import io.github.tieo.arbay.ui.AdaptiveFormSheet
import io.github.tieo.arbay.ui.LocalDesktopMode
import io.github.tieo.arbay.ui.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.ProductViewModel

/**
 * Best-effort make/model nodes for prefilling the vehicle-search form's dropdowns, from the text of
 * a search already known to be a car search — see [ResultsView.isCar]. Never used to decide whether
 * something IS a car search: that used to be guessed from whether a make's name appeared anywhere
 * in the query text, which misfires on any make that is also an ordinary word (RAM is a real
 * vehicle brand and also what a "32GB SODIMM RAM" listing calls itself). A wrong guess here only
 * means the wrong dropdown is preselected in a form the user is looking straight at; it can no
 * longer silently reroute an unrelated search into vehicle mode.
 */
private fun resolveCarNodes(query: String): Pair<CarMakeNode?, CarModelNode?> {
    val q = query.trim().lowercase()
    fun leads(word: String) = q == word || q.startsWith("$word ")
    val make = CarTaxonomyStore.taxonomy.makes.firstOrNull { m ->
        val n = m.name.lowercase()
        leads(n) || (n == "volkswagen" && leads("vw"))
    }
    val model = make?.models?.firstOrNull { q.contains(it.name.lowercase()) }
    return make to model
}

/**
 * A search whose results are on screen. Every way in — opening a bookmark, previewing a product,
 * running the car form — produces one of these, so the results sheet is wired once instead of
 * three times with three different notions of what saving, editing and blocking mean.
 *
 * [isCar] marks it a vehicle search and gives the sheet its car view; it is carried explicitly
 * from wherever the search actually came from, never re-derived from the query text. The bookmark
 * behind it, if any, is looked up live from the saved searches by query text rather than carried
 * here, so saving and removing take effect without rebuilding this.
 */
private data class ResultsView(
    val name: String,
    val query: String,
    val platforms: List<PlatformId>?,
    val category: MarketGroup,
    val make: CarMakeNode? = null,
    val model: CarModelNode? = null,
    val filters: CarFilters? = null,
    // Alternate phrasings and excluded words this search carries — a catalogue product's own
    // data, or whatever a bookmark/history entry was last narrowed to. Never embedded in [query]
    // itself.
    val aliases: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    /** Back leads to the car form it was run from, else to discovery, else nowhere. */
    val fromCarForm: Boolean = false,
    val fromDiscovery: Boolean = false,
    // What this saved search had found since it was last opened, captured at the moment of opening
    // because opening is what clears it. Empty for every other way in, which has no such backlog.
    val newListingIds: Set<String> = emptySet(),
    // Those findings themselves, as the watch stored them, when the results were opened from the
    // "n new" badge. Non-null means this view does not crawl to fill itself. Carried here so that
    // every way of closing the results drops them with the view.
    val stored: List<Listing>? = null,
) {
    /** Whether this is the vehicle-search view — computed from [category] rather than stored
     *  alongside it, so the two can never disagree. */
    val isCar: Boolean get() = category == MarketGroup.VEHICLES

    companion object {
        /** The results of a saved search. A vehicle bookmark gets the car view even with no
         *  filters set yet, so the filters can be added from there. */
        fun of(product: TrackedProduct, newListingIds: Set<String> = emptySet()): ResultsView {
            val category = product.searchQuery.category
            val isCar = category == MarketGroup.VEHICLES
            val (make, model) = if (isCar) resolveCarNodes(product.searchQuery.text) else null to null
            return ResultsView(
                name = product.name,
                query = product.searchQuery.text,
                platforms = product.searchQuery.platforms,
                category = category,
                make = make,
                model = model,
                filters = if (isCar) product.searchQuery.toCarFilters() ?: CarFilters() else null,
                aliases = product.searchQuery.aliases,
                excludeKeywords = product.searchQuery.excludeKeywords,
                newListingIds = newListingIds,
            )
        }

        /** The results of a search that was run before but never saved — same shape as reopening a
         *  bookmark, since a history entry carries the same [SearchQuery]. */
        fun of(entry: SearchHistoryEntry): ResultsView {
            val category = entry.searchQuery.category
            val isCar = category == MarketGroup.VEHICLES
            val (make, model) = if (isCar) resolveCarNodes(entry.searchQuery.text) else null to null
            return ResultsView(
                name = entry.name,
                query = entry.searchQuery.text,
                platforms = entry.searchQuery.platforms,
                category = category,
                make = make,
                model = model,
                filters = if (isCar) entry.searchQuery.toCarFilters() ?: CarFilters() else null,
                aliases = entry.searchQuery.aliases,
                excludeKeywords = entry.searchQuery.excludeKeywords,
            )
        }

        /** The results of a query typed into the plain search box or a catalogue product. Never
         *  the vehicle-search view (that only opens through the car form) — [category] still
         *  distinguishes a catalogue car (e.g. "VW Golf 8") from an ordinary product search, since
         *  that decides which markets it defaults to reaching. Aliases/excludeKeywords are a
         *  catalogue product's own data (empty for a plain typed search). */
        fun of(
            name: String,
            query: String,
            platforms: List<PlatformId>?,
            category: MarketGroup,
            fromDiscovery: Boolean = false,
            aliases: List<String> = emptyList(),
            excludeKeywords: List<String> = emptyList(),
        ): ResultsView = ResultsView(
            name = name,
            query = query,
            platforms = platforms,
            category = category,
            fromDiscovery = fromDiscovery,
            aliases = aliases,
            excludeKeywords = excludeKeywords,
        )
    }
}

/** The results sheet's own slice of the "where we are" debug snapshot — [ResultsView] is private
 *  to this file, so its snapshot type lives here too rather than in the debug package. */
@Serializable
private data class ResultsScreenSnapshot(val name: String, val query: String, val isCar: Boolean)

@Serializable
private data class ScreenDebugSnapshot(
    val showDiscovery: Boolean,
    val showAddSheet: Boolean,
    val showSettings: Boolean,
    val showFreeItems: Boolean,
    val showCarSearch: Boolean,
    val editingProductId: String?,
    val results: ResultsScreenSnapshot?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    productViewModel: ProductViewModel,
    listingViewModel: ListingViewModel,
    freeItemViewModel: FreeItemViewModel,
    client: ArbayClient,
) {
    val products by productViewModel.products.collectAsState()
    val productStatus by productViewModel.status.collectAsState()
    val scope = rememberCoroutineScope()
    // Only markets a crawler exists for are worth offering: selecting one without adds nothing to
    // a search and says nothing about why.
    val marketCapabilities by listingViewModel.capabilities.collectAsState()
    val offerableMarkets = remember(marketCapabilities) {
        val crawled = marketCapabilities.values.filter { it.crawled }.map { it.platform }
        crawled.ifEmpty { PlatformId.entries }
    }
    // The saved bookmark whose search text matches a query, if any — drives the header bookmark
    // toggle so a fresh search can be saved and an already-saved one removed, from the same place.
    fun savedFor(query: String): TrackedProduct? =
        products.firstOrNull { it.searchQuery.text.trim().equals(query.trim(), ignoreCase = true) }
    val loading by productViewModel.loading.collectAsState()
    val error by productViewModel.error.collectAsState()
    val freeItemProfile by freeItemViewModel.profile.collectAsState()
    val freeItemStats by freeItemViewModel.stats.collectAsState()
    var showDiscovery by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var addSheetPrefill by remember { mutableStateOf<KnownProduct?>(null) }
    var addSheetInitialQuery by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showFreeItems by remember { mutableStateOf(false) }
    // The search whose results are on screen, whatever opened it. Null = no results sheet.
    var results by remember { mutableStateOf<ResultsView?>(null) }
    var cameFromDiscovery by remember { mutableStateOf(false) }
    var showCarSearch by remember { mutableStateOf(false) }
    var carQuery by remember { mutableStateOf("") }
    var carName by remember { mutableStateOf("") }
    var carPlatforms by remember { mutableStateOf<List<PlatformId>?>(null) }
    var carFilters by remember { mutableStateOf<CarFilters?>(null) }
    // The bookmark whose platforms are currently loaded, so re-opening the SAME bookmark's edit keeps
    // an unsaved market change instead of reloading the saved set each time.
    var carPlatformsLoadedFor by remember { mutableStateOf<String?>(null) }
    var carMake by remember { mutableStateOf<CarMakeNode?>(null) }
    var carModel by remember { mutableStateOf<CarModelNode?>(null) }
    // Non-null while editing an existing bookmark: the save action updates this one instead of
    // creating a new bookmark. Set from the card's Edit button (and the edit-filters path).
    var editingProduct by remember { mutableStateOf<TrackedProduct?>(null) }
    // Non-null while the auto-fetch/notification-subfilter sheet is open for this bookmark.
    var alertsProduct by remember { mutableStateOf<TrackedProduct?>(null) }
    // The bookmark behind the open results, if the query is saved. Looked up live, so saving or
    // removing one takes effect without rebuilding the view.
    val resultsBookmark = results?.let { savedFor(it.query) }
    // A search's filters live somewhere the moment it opens: on the bookmark if it is saved, in
    // history otherwise. This is the "otherwise" — looked up live, same as the bookmark above.
    val searchHistory by SearchHistoryStore.entries.collectAsState()
    val resultsHistoryEntry = results?.let { view -> searchHistory.firstOrNull {
        it.searchQuery.text.trim().equals(view.query.trim(), ignoreCase = true)
    } }
    // Blocked keywords for the open results, held locally so edits filter live before they are
    // saved. Seeded from the bookmark, else from history; a search with neither starts with none.
    var resultsBlockedTerms by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(results?.query, resultsBookmark?.id) {
        resultsBlockedTerms = resultsBookmark?.searchQuery?.excludeKeywords
            ?: resultsHistoryEntry?.searchQuery?.excludeKeywords
            ?: emptyList()
    }

    // "Where we are" for the debug dump (debug/DebugRegistry.kt): which sheet is open and what it
    // was opened with. Registered fresh on every recomposition of this screen, which is exactly
    // when any of these values can change, so it is never stale by the time a dump is requested.
    DebugRegistry.set(
        "screen",
        debugJson.encodeToString(
            ScreenDebugSnapshot(
                showDiscovery = showDiscovery,
                showAddSheet = showAddSheet,
                showSettings = showSettings,
                showFreeItems = showFreeItems,
                showCarSearch = showCarSearch,
                editingProductId = editingProduct?.id,
                results = results?.let {
                    ResultsScreenSnapshot(name = it.name, query = it.query, isCar = it.isCar)
                },
            ),
        ),
    )

    LaunchedEffect(Unit) {
        productViewModel.loadProducts()
    }

    fun openAddSheet(prefill: KnownProduct? = null, initialQuery: String = "") {
        cameFromDiscovery = showDiscovery
        showDiscovery = false
        addSheetPrefill = prefill
        editingProduct = null
        addSheetInitialQuery = if (prefill != null) prefill.searchQuery else initialQuery
        showAddSheet = true
    }

    fun openResults(view: ResultsView) {
        cameFromDiscovery = showDiscovery
        showDiscovery = false
        val opened = view.copy(fromDiscovery = view.fromDiscovery || cameFromDiscovery)
        // Recorded before showing, so the results sheet's own filter/history lookups already see
        // this search. Keeps whatever it was narrowed to last time it ran (recordOpen only refreshes
        // the name, platforms and vehicle criteria), so reopening a history row does not reset it.
        // Empty here means "this way in doesn't know about aliases/excludes" (a plain typed
        // search, a bookmark with none set), not "clear whatever history already has" — null
        // keeps recordOpen's existing-entry merge, same as it already does for carFilters.
        SearchHistoryStore.recordOpen(
            opened.name, opened.query, opened.platforms, opened.filters, opened.category,
            aliases = opened.aliases.ifEmpty { null }, excludeKeywords = opened.excludeKeywords.ifEmpty { null },
        )
        results = opened
    }

    fun openPreview(name: String, query: String, platforms: List<PlatformId>?) {
        openResults(ResultsView.of(name, query, platforms, category = MarketGroup.GENERAL))
    }

    fun openPreview(product: KnownProduct) {
        val category = if (product.category == ProductCategory.CARS) MarketGroup.VEHICLES else MarketGroup.GENERAL
        openResults(
            ResultsView.of(
                product.displayName, product.searchQuery, product.effectivePlatforms, category,
                aliases = product.aliases, excludeKeywords = product.excludeKeywords,
            ),
        )
    }

    /** Open the car form on a search, prefilled. The one way in, from the bookmark card, from the
     *  results sheet's Filters chip, and from discovery. */
    fun openCarEditor(view: ResultsView, bookmark: TrackedProduct?) {
        editingProduct = bookmark
        carName = view.name
        carQuery = view.query
        // Load the saved markets only when switching to a different bookmark, so a deselection
        // survives re-opening this one's edit.
        if (bookmark == null || carPlatformsLoadedFor != bookmark.id) {
            carPlatforms = view.platforms
            carPlatformsLoadedFor = bookmark?.id
        }
        carFilters = view.filters ?: CarFilters()
        carMake = view.make
        carModel = view.model
        results = null
        showCarSearch = true
    }

    val isDesktop = LocalDesktopMode.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        modifier = if (isDesktop) {
            Modifier.focusRequester(focusRequester).focusable().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.isCtrlPressed || event.isMetaPressed)) {
                    when (event.key) {
                        Key.K -> { showDiscovery = true; true }
                        Key.Comma -> { showSettings = true; true }
                        else -> false
                    }
                } else if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    when {
                        showDiscovery -> { showDiscovery = false; true }
                        showAddSheet -> { showAddSheet = false; true }
                        showSettings -> { showSettings = false; true }
                        showFreeItems -> { showFreeItems = false; true }
                        results != null -> { results = null; true }
                        else -> false
                    }
                } else false
            }
        } else Modifier,
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            modifier = Modifier
                .then(if (isDesktop) Modifier.widthIn(max = 800.dp) else Modifier.fillMaxWidth()),
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Arbay",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )

                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text(if (isDesktop) "Settings (Ctrl+,)" else "Settings") } },
                    state = rememberTooltipState(),
                ) {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Outlined.Settings, "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Error
            error?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            // Product list, takes all available space
            val hasFreeItemProfile = freeItemProfile != null
            val hasContent = products.isNotEmpty() || hasFreeItemProfile
            if (loading && products.isEmpty() && !hasFreeItemProfile) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (!hasContent) {
                Box(
                    Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Inventory2, null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        // An unreachable server is not an empty account. Saying "no bookmarks yet"
                        // to someone whose bookmarks are sitting on a server that did not answer
                        // reads as having lost them.
                        Text(
                            if (error != null) "Bookmarks could not be loaded" else "No bookmarks yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                error != null -> "The server did not answer. Yours are still there; " +
                                    "this screen fills in as soon as it can be reached."
                                isDesktop -> "Click the search bar below or press Ctrl+K"
                                else -> "Tap the search bar below to get started"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    // Free Items card, pinned above the bookmarks when a profile is set
                    if (hasFreeItemProfile) {
                        item(key = "__free_items__") {
                            FreeItemsMainCard(
                                profile = freeItemProfile!!,
                                stats = freeItemStats,
                                onClick = { showFreeItems = true },
                            )
                        }
                    }
                    items(products, key = { it.id }) { product ->
                        ProductCard(
                            product = product,
                            onDelete = { productViewModel.deleteProduct(product.id) },
                            status = productStatus[product.id],
                            onViewListings = {
                                // Read the backlog before marking it opened, which clears it.
                                val newIds = productStatus[product.id]?.newListingIds.orEmpty().toSet()
                                results = ResultsView.of(product, newIds)
                                productViewModel.markOpened(product.id)
                            },
                            // The "n new" badge opens the findings themselves, fetched from what
                            // the watch stored rather than searched for again. Nothing stored (an
                            // older server, an unreachable one) falls through to the ordinary
                            // view, so the badge never becomes a tap that does nothing.
                            onViewNew = {
                                val newIds = productStatus[product.id]?.newListingIds.orEmpty().toSet()
                                scope.launch {
                                    val stored = runCatching { client.getNewListings(product.id) }
                                        .getOrDefault(emptyList())
                                    results = ResultsView.of(product, newIds)
                                        .copy(stored = stored.ifEmpty { null })
                                    productViewModel.markOpened(product.id)
                                }
                            },
                            onEdit = {
                                editingProduct = product
                                val view = ResultsView.of(product)
                                // A vehicle bookmark edits in the structured car form, anything
                                // else in the add/edit sheet.
                                if (view.isCar) openCarEditor(view, product) else showAddSheet = true
                            },
                            onAlerts = { alertsProduct = product },
                        )
                    }
                }
            }

            // Search bar at the bottom, the single entry point for adding products
            Surface(
                onClick = { showDiscovery = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Search, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Search or add a product...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (isDesktop) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                "Ctrl+K",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        }
    }

    // Discovery sheet
    if (showDiscovery) {
        DiscoverySheet(
            onDismiss = { showDiscovery = false },
            onProductSelected = { product -> openPreview(product) },
            onCustomSearch = { query -> openAddSheet(initialQuery = query) },
            onLiveSearch = { query -> openPreview(query, query, null) },
            // The explicit override for a bare model name ("Sprinter", "Golf") that automatic
            // car detection misses because it has no make in it — chosen, not guessed.
            onLiveVehicleSearch = { query ->
                openResults(ResultsView.of(
                    query, query,
                    MarketSets.platformsIn(MarketGroup.VEHICLES, SearchCountries.current.countries),
                    category = MarketGroup.VEHICLES,
                ))
            },
            onFreeItems = { showFreeItems = true },
            countries = SearchCountries.current.countries,
            onCountriesChange = { chosen ->
                SearchCountries.current = MarketSettings(chosen)
                scope.launch {
                    runCatching { client.updateMarketSettings(MarketSettings(chosen)) }
                }
            },
            history = searchHistory,
            onOpenHistory = { entry -> openResults(ResultsView.of(entry)) },
            onRemoveHistory = { query -> SearchHistoryStore.remove(query) },
            onClearHistory = { SearchHistoryStore.clear() },
            onCarSearch = {
                // Fresh car search: clear any state left from a previous edit so the form
                // opens empty, not prefilled with the last bookmark's make/model/filters.
                editingProduct = null
                carName = ""
                carQuery = ""
                // carPlatforms is kept: the market selection is a sticky preference, not per-search
                // state, so a deselected platform stays deselected across searches.
                carPlatformsLoadedFor = null // a fresh search is not tied to a bookmark
                carFilters = null
                carMake = null
                carModel = null
                cameFromDiscovery = true
                showDiscovery = false
                showCarSearch = true
            },
        )
    }

    // Free Items sheet
    if (showFreeItems) {
        FreeItemsSheet(
            viewModel = freeItemViewModel,
            onDismiss = { showFreeItems = false },
        )
    }

    // Settings. The gear and Ctrl+, both set this, and nothing drew the sheet: the whole screen —
    // which server to talk to, the display currency, search depth, what may raise a notification —
    // could not be reached from the running app at all.
    if (showSettings) {
        SettingsSheet(
            client = client,
            onDismiss = { showSettings = false },
            // A different server means different saved searches, so nothing on screen still holds.
            onServerUrlChanged = {
                productViewModel.loadProducts()
                freeItemViewModel.loadProfile()
            },
        )
    }

    // Car search form
    if (showCarSearch) {
        CarSearchSheet(
            onDismiss = {
                showCarSearch = false
                cameFromDiscovery = false
                editingProduct = null
            },
            onBack = if (cameFromDiscovery) {
                {
                    showCarSearch = false
                    cameFromDiscovery = false
                    showDiscovery = true
                }
            } else null,
            onSearch = { name, query, platforms, filters, make, model ->
                carName = name
                carQuery = query
                carPlatforms = platforms
                carFilters = filters
                carMake = make
                carModel = model
                showCarSearch = false
                // Running the form on a bookmark edits that bookmark, the same way narrowing the
                // price band or blocking a word from its results does. A search with no bookmark
                // behind it keeps its filters in the view until it is saved.
                editingProduct?.let { saved ->
                    productViewModel.updateProduct(
                        saved.copy(
                            name = name,
                            searchQuery = saved.searchQuery.withCarFilters(filters).copy(
                                text = query,
                                platforms = platforms
                                    ?: MarketSets.platformsIn(MarketGroup.VEHICLES, SearchCountries.current.countries),
                                category = MarketGroup.VEHICLES,
                            ),
                        ),
                    )
                }
                // Reaching this callback IS running the vehicle form, so this is always a car
                // search — recorded the same way any other search is, so it shows up in Recent.
                SearchHistoryStore.recordOpen(name, query, platforms, filters, category = MarketGroup.VEHICLES)
                results = ResultsView(
                    name = name,
                    query = query,
                    platforms = platforms,
                    category = MarketGroup.VEHICLES,
                    make = make,
                    model = model,
                    filters = filters,
                    fromCarForm = true,
                )
            },
            initialMake = carMake,
            initialModel = carModel,
            initialFilters = carFilters,
            initialPlatforms = carPlatforms,
            onPlatformsChange = { carPlatforms = it },
        )
    }

    // Adding a search by hand, and editing one that is not a vehicle. Both set showAddSheet and
    // nothing rendered it, so "Custom search" and the Edit button on a non-vehicle bookmark did
    // nothing at all.
    if (showAddSheet) {
        AddProductSheet(
            markets = offerableMarkets,
            prefill = addSheetPrefill,
            editProduct = editingProduct,
            initialQuery = addSheetInitialQuery,
            onDismiss = {
                showAddSheet = false
                addSheetPrefill = null
                addSheetInitialQuery = ""
                editingProduct = null
            },
            onBack = if (cameFromDiscovery) {
                {
                    showAddSheet = false
                    cameFromDiscovery = false
                    showDiscovery = true
                }
            } else null,
            onConfirm = { name, query, platforms, identifiers ->
                val existing = editingProduct
                if (existing != null) {
                    productViewModel.updateProduct(
                        existing.copy(
                            name = name,
                            searchQuery = existing.searchQuery.copy(text = query, platforms = platforms),
                            identifiers = identifiers,
                        ),
                    )
                } else {
                    productViewModel.createProduct(
                        name = name,
                        searchText = query,
                        platforms = platforms,
                        category = if (addSheetPrefill?.category == ProductCategory.CARS) MarketGroup.VEHICLES else MarketGroup.GENERAL,
                        identifiers = identifiers,
                        aliases = addSheetPrefill?.aliases ?: emptyList(),
                        excludeKeywords = addSheetPrefill?.excludeKeywords ?: emptyList(),
                    )
                }
                showAddSheet = false
                addSheetPrefill = null
                addSheetInitialQuery = ""
                editingProduct = null
            },
        )
    }

    alertsProduct?.let { product ->
        // Read live off the product list, not the snapshot the sheet was opened with, so a toggle
        // takes effect on screen the moment updateProduct's reload lands.
        val current = products.firstOrNull { it.id == product.id } ?: product
        SearchAlertsSheet(
            productName = current.name,
            autoFetch = current.autoFetch,
            onAutoFetchChange = { productViewModel.setAutoFetch(current, it) },
            subfilters = current.notificationSubfilters,
            onSubfiltersChange = { productViewModel.setNotificationSubfilters(current, it) },
            onDismiss = { alertsProduct = null },
        )
    }

    // Results, for every way in: a saved bookmark, a preview of something not saved yet, or a
    // fresh run of the car form. One sheet, wired once — saving, editing filters and blocking a
    // word mean the same thing whichever door the user came through.
    results?.let { view ->
        val bookmark = resultsBookmark
        ListingsSheet(
            productName = view.name,
            searchQuery = view.query,
            listingViewModel = listingViewModel,
            platforms = view.platforms,
            carFilters = view.filters,
            newListingIds = view.newListingIds,
            storedListings = view.stored,
            aliases = (bookmark?.searchQuery ?: resultsHistoryEntry?.searchQuery)?.aliases ?: view.aliases,
            // A vehicle search can always reach the form, even with no filters set yet, so they
            // can be added from the results.
            onEditFilters = if (view.isCar) {
                { openCarEditor(view, bookmark) }
            } else null,
            // A vehicle search edits its term through the car form's make/model fields, not here.
            onEditQuery = if (view.isCar) null else { newQuery ->
                // The name followed the term until someone gave it its own — keep following it.
                val followsTerm = view.name.equals(view.query, ignoreCase = true)
                val newName = if (followsTerm) newQuery else view.name
                if (bookmark != null) {
                    productViewModel.updateProduct(
                        bookmark.copy(
                            name = newName,
                            searchQuery = bookmark.searchQuery.copy(text = newQuery, aliases = emptyList()),
                        ),
                    )
                } else {
                    // Read what this search carries BEFORE removing its old-text entry — removing
                    // first would erase the very excludeKeywords being carried over. Aliases do NOT
                    // carry over: they name alternate spellings of the OLD term specifically (e.g.
                    // "XT5" for "Fujifilm X-T5"), which says nothing about whatever the term is
                    // rewritten to.
                    val base = SearchHistoryStore.baseQuery(view.query, view.platforms, view.filters, view.category)
                        .copy(text = newQuery, aliases = emptyList())
                    SearchHistoryStore.remove(view.query)
                    SearchHistoryStore.record(newName, base)
                }
                results = view.copy(name = newName, query = newQuery)
            },
            // The structured stand-in for typing "OR" into the search box — a listing matching
            // any one of these counts as a match. Available for a vehicle search too: the car
            // form's make/model fields narrow by model, not by alternate model-number spellings
            // ("314" vs "315").
            onAliasesChange = { updated ->
                if (bookmark != null) {
                    productViewModel.updateProduct(bookmark.copy(searchQuery = bookmark.searchQuery.copy(aliases = updated)))
                } else {
                    val base = SearchHistoryStore.baseQuery(view.query, view.platforms, view.filters, view.category)
                    SearchHistoryStore.record(view.name, base.copy(aliases = updated))
                }
            },
            // A search's filters live on the bookmark once it is saved; until then they live in
            // history, which openResults already seeded, so this is never null for an open search.
            savedFilters = bookmark?.searchQuery ?: resultsHistoryEntry?.searchQuery,
            onFiltersPersist = { query: SearchQuery ->
                if (bookmark != null) productViewModel.updateProduct(bookmark.copy(searchQuery = query))
                else SearchHistoryStore.record(view.name, query)
            },
            blockedTerms = resultsBlockedTerms,
            onBlockedTermsChange = { updated ->
                resultsBlockedTerms = updated
                if (bookmark != null) {
                    productViewModel.setBlockedKeywords(bookmark, updated)
                } else {
                    val base = SearchHistoryStore.baseQuery(view.query, view.platforms, view.filters, view.category)
                    SearchHistoryStore.record(view.name, base.copy(excludeKeywords = updated))
                }
            },
            isBookmarked = bookmark != null,
            onToggleBookmark = {
                if (bookmark != null) {
                    productViewModel.deleteProduct(bookmark.id)
                } else {
                    productViewModel.createProduct(
                        name = view.name.ifBlank { view.query },
                        searchText = view.query,
                        // A plain search (no catalogue/car platform list of its own) is bookmarked
                        // with the same markets it was actually searched with — never "every
                        // platform including car-only and real-estate sites", which the
                        // saved-search monitor would then re-run forever regardless of relevance.
                        platforms = view.platforms
                            ?: MarketSets.platformsIn(view.category, SearchCountries.current.countries),
                        category = view.category,
                        carFilters = view.filters,
                        excludeKeywords = resultsBlockedTerms,
                        aliases = resultsHistoryEntry?.searchQuery?.aliases ?: view.aliases,
                    )
                }
            },
            onDismiss = {
                results = null
                editingProduct = null
                cameFromDiscovery = false
            },
            onBack = when {
                view.fromCarForm -> {
                    {
                        results = null
                        showCarSearch = true
                    }
                }
                view.fromDiscovery -> {
                    {
                        results = null
                        cameFromDiscovery = false
                        showDiscovery = true
                    }
                }
                else -> null
            },
        )
    }
}

/** How long ago something happened, in the coarsest unit that still says it. */
private fun ago(millis: Long): String {
    val minutes = ((kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - millis) / 60_000L)
        .coerceAtLeast(0L)
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 60 * 24 -> "${minutes / 60} h ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}

// ── Product Card ─────────────────────────────────────────────

@Composable
internal fun ProductCard(
    product: TrackedProduct,
    onDelete: () -> Unit,
    onViewListings: () -> Unit,
    onEdit: () -> Unit,
    onAlerts: () -> Unit,
    // What this search has found since it was last opened, when the server is watching it.
    status: SavedSearchStatus? = null,
    // Open just what the watch found, from what it stored. Separate from [onViewListings], which
    // runs the search again.
    onViewNew: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove bookmark?") },
            text = { Text("\"${product.name}\" will be deleted. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDelete() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    // Whole card opens the listings; Edit/Remove are explicit trailing actions.
    Card(
        onClick = onViewListings,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (status?.watched == true) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.size(10.dp),
                ) {}

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        product.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    // Only show the query when it adds info beyond the label (they're often identical).
                    val platformLabel = "${product.searchQuery.platforms.size} platforms"
                    val queryDiffers = product.searchQuery.text.trim()
                        .equals(product.name.trim(), ignoreCase = true).not()
                    Text(
                        if (queryDiffers) "${product.searchQuery.text}  ·  $platformLabel" else platformLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    // When the search last ran, so an empty result reads as "nothing new" rather
                    // than as a search that never happened.
                    status?.let { s ->
                        val ran = s.lastRunAtMillis?.let { ago(it) }
                        Text(
                            when {
                                !s.watched -> "not watched"
                                ran != null -> "checked $ran"
                                else -> "watched, not run yet"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                if ((status?.newSinceOpened ?: 0) > 0) {
                    Surface(
                        onClick = onViewNew,
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            "${status!!.newSinceOpened} new",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                IconButton(onClick = onAlerts) {
                    Icon(
                        if (product.autoFetch.enabled) Icons.Default.NotificationsActive else Icons.Outlined.Notifications,
                        "Alerts",
                        modifier = Modifier.size(20.dp),
                        tint = if (product.autoFetch.enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Outlined.Edit, "Edit",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Outlined.Delete, "Remove",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

// ── Free Items Card (Main Screen) ────────────────────────────

@Composable
internal fun FreeItemsMainCard(
    profile: FreeItemProfile,
    stats: FreeItemStats?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            // Accent bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        MaterialTheme.colorScheme.tertiary,
                        RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
                    ),
            )

            Column(modifier = Modifier.weight(1f).padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CardGiftcard, null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Free Items",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            profile.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (stats != null && stats.totalLoved > 0) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFE91E63).copy(alpha = 0.15f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(Icons.Default.Favorite, null, modifier = Modifier.size(12.dp), tint = Color(0xFFE91E63))
                                Text("${stats.totalLoved}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFE91E63))
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }

                    Icon(
                        Icons.Default.ChevronRight, null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Location + stats summary
                if (profile.location != null || stats != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (profile.location != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.LocationOn, null, modifier = Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(2.dp))
                                Text("${profile.location} · ${profile.radiusKm} km", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (stats != null && stats.totalSeen > 0) {
                            Text("${stats.totalSeen} reviewed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

            }
        }
    }
}

// ── Add Product Sheet ────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProductSheet(
    prefill: KnownProduct? = null,
    editProduct: TrackedProduct? = null,
    initialQuery: String = "",
    // Only markets a crawler exists for.
    markets: List<PlatformId> = PlatformId.entries,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    onConfirm: (name: String, query: String, platforms: List<PlatformId>, identifiers: ProductIdentifier) -> Unit,
) {
    var query by remember { mutableStateOf(editProduct?.searchQuery?.text ?: prefill?.searchQuery ?: initialQuery) }
    var name by remember { mutableStateOf(editProduct?.name ?: prefill?.displayName ?: initialQuery) }
    // Auto-fill name from query when user hasn't manually edited the name
    var nameManuallyEdited by remember { mutableStateOf(prefill != null || editProduct != null) }
    var gtinText by remember { mutableStateOf(editProduct?.identifiers?.gtins?.joinToString(", ") ?: prefill?.gtins?.joinToString(", ") ?: "") }
    var mpn by remember { mutableStateOf(editProduct?.identifiers?.mpn ?: prefill?.mpn ?: "") }
    var showIdentifiers by remember {
        mutableStateOf(
            (editProduct?.identifiers?.let { it.mpn != null || it.gtins.isNotEmpty() } ?: false) ||
                (prefill != null && (prefill.mpn != null || prefill.gtins.isNotEmpty())),
        )
    }
    val selectedPlatforms = remember {
        mutableStateListOf<PlatformId>().apply {
            // A fresh custom search (no catalogue prefill) defaults to general marketplaces, not
            // every crawlable platform — car-only and real-estate sites would otherwise get
            // queried for a search they could never match. Still there to add by hand below.
            addAll(
                editProduct?.searchQuery?.platforms
                    ?: prefill?.effectivePlatforms
                    ?: markets.filter { it in MarketSets.general },
            )
        }
    }

    AdaptiveFormSheet(onDismiss = onDismiss) {
        if (onBack != null) {
            BackHandler(onBack = onBack)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                when {
                    editProduct != null -> "Edit bookmark"
                    prefill != null -> "Save ${prefill.displayName}"
                    else -> "Save Search"
                },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(20.dp))

            OutlinedTextField(
                value = query, onValueChange = {
                    query = it
                    if (!nameManuallyEdited) {
                        // Auto-fill name: use the positive part of the query (strip -exclusions)
                        name = it.split(" ")
                            .filter { w -> w.isNotBlank() && !w.startsWith("-") }
                            .joinToString(" ")
                    }
                },
                label = { Text("Search query") },
                placeholder = { Text("e.g. WH-1000XM4 -case -cover") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name, onValueChange = { name = it; nameManuallyEdited = true },
                label = { Text("Label") },
                placeholder = { Text("e.g. Sony WH-1000XM4") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null) },
            )

            Spacer(Modifier.height(12.dp))

            // GTIN/MPN toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showIdentifiers = !showIdentifiers }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.QrCode, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Product identifiers (GTIN / MPN)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text("Optional", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(4.dp))
                Icon(if (showIdentifiers) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            AnimatedVisibility(visible = showIdentifiers) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = gtinText, onValueChange = { gtinText = it },
                        label = { Text("GTIN / EAN / UPC") },
                        placeholder = { Text("e.g. 4548736112162") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        supportingText = { Text("Separate multiple with commas") },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = mpn, onValueChange = { mpn = it },
                        label = { Text("MPN (Manufacturer Part Number)") },
                        placeholder = { Text("e.g. WH1000XM4/B") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Platforms (${selectedPlatforms.size}/${markets.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { selectedPlatforms.clear(); selectedPlatforms.addAll(markets) }) {
                        Text("All", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { selectedPlatforms.clear() }) {
                        Text("None", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Grouped by what each platform actually is, not one flat wall of ~35 unlabeled
            // chips — MarketSets.groupOf picks one group for a platform in more than one
            // (Kleinanzeigen: general and vehicles), so its chip shows once.
            val groupedMarkets = remember(markets) {
                val byGroup = markets.groupBy { MarketSets.groupOf(it) }
                MarketGroup.entries.mapNotNull { group -> byGroup[group]?.let { group.label to it } }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                groupedMarkets.forEach { (label, platforms) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            platforms.forEach { platform ->
                                FilterChip(
                                    selected = platform in selectedPlatforms,
                                    onClick = {
                                        if (platform in selectedPlatforms) selectedPlatforms.remove(platform)
                                        else selectedPlatforms.add(platform)
                                    },
                                    label = { Text(platform.displayName, style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = {
                                        if (platform in selectedPlatforms) Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val gtins = gtinText.split(",", " ", ";").map { it.trim() }.filter { it.isNotBlank() }
                    val identifiers = ProductIdentifier(gtins = gtins, mpn = mpn.ifBlank { null })
                    onConfirm(name, query, selectedPlatforms.toList(), identifiers)
                },
                enabled = name.isNotBlank() && query.isNotBlank() && selectedPlatforms.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Save")
            }
        }
    }
}
