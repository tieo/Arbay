package io.github.tieo.arbay.ui.screen

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
import io.github.tieo.arbay.model.FreeItemProfile
import io.github.tieo.arbay.model.FreeItemStats
import io.github.tieo.arbay.model.PlatformId
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

/** Best-effort make/model nodes from a saved search's query text, for prefilling the editor. */
private fun resolveCarNodes(query: String): Pair<CarMakeNode?, CarModelNode?> {
    val q = query.lowercase()
    val make = CarTaxonomyStore.taxonomy.makes.firstOrNull { m ->
        val n = m.name.lowercase()
        q.contains(n) || (n == "volkswagen" && Regex("""\bvw\b""").containsMatchIn(q))
    }
    val model = make?.models?.firstOrNull { q.contains(it.name.lowercase()) }
    return make to model
}

/**
 * A search whose results are on screen. Every way in — opening a bookmark, previewing a product,
 * running the car form — produces one of these, so the results sheet is wired once instead of
 * three times with three different notions of what saving, editing and blocking mean.
 *
 * A non-null [filters] marks it a vehicle search and gives the sheet its car view; the bookmark
 * behind it, if any, is looked up live from the saved searches by query text rather than carried
 * here, so saving and removing take effect without rebuilding this.
 */
private data class ResultsView(
    val name: String,
    val query: String,
    val platforms: List<PlatformId>?,
    val make: CarMakeNode? = null,
    val model: CarModelNode? = null,
    val filters: CarFilters? = null,
    /** Back leads to the car form it was run from, else to discovery, else nowhere. */
    val fromCarForm: Boolean = false,
    val fromDiscovery: Boolean = false,
) {
    val isCar: Boolean get() = filters != null

    companion object {
        /** The results of a saved search. A vehicle query gets the car view even with no filters
         *  set yet, so the filters can be added from there. */
        fun of(product: TrackedProduct): ResultsView {
            val (make, model) = resolveCarNodes(product.searchQuery.text)
            return ResultsView(
                name = product.name,
                query = product.searchQuery.text,
                platforms = product.searchQuery.platforms,
                make = make,
                model = model,
                filters = if (make != null) product.searchQuery.toCarFilters() ?: CarFilters() else null,
            )
        }

        /** The results of a query that is not saved yet. */
        fun of(
            name: String,
            query: String,
            platforms: List<PlatformId>?,
            fromDiscovery: Boolean = false,
        ): ResultsView {
            val (make, model) = resolveCarNodes(query)
            return ResultsView(
                name = name,
                query = query,
                platforms = platforms,
                make = make,
                model = model,
                filters = if (make != null) CarFilters() else null,
                fromDiscovery = fromDiscovery,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    productViewModel: ProductViewModel,
    listingViewModel: ListingViewModel,
    freeItemViewModel: FreeItemViewModel,
    client: ArbayClient,
) {
    val products by productViewModel.products.collectAsState()
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
    // The bookmark behind the open results, if the query is saved. Looked up live, so saving or
    // removing one takes effect without rebuilding the view.
    val resultsBookmark = results?.let { savedFor(it.query) }
    // Blocked keywords for the open results, held locally so edits filter live before they are
    // saved. Seeded from the bookmark; a search not saved yet starts with none.
    var resultsBlockedTerms by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(results?.query, resultsBookmark?.id) {
        resultsBlockedTerms = resultsBookmark?.searchQuery?.excludeKeywords ?: emptyList()
    }

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
        results = view.copy(fromDiscovery = view.fromDiscovery || cameFromDiscovery)
    }

    fun openPreview(name: String, query: String, platforms: List<PlatformId>?) {
        openResults(ResultsView.of(name, query, platforms))
    }

    fun openPreview(product: KnownProduct) {
        openPreview(product.displayName, product.searchQuery, product.effectivePlatforms)
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
                        Text(
                            "No bookmarks yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isDesktop) "Click the search bar below or press Ctrl+K"
                            else "Tap the search bar below to get started",
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
                            onViewListings = { results = ResultsView.of(product) },
                            onEdit = {
                                editingProduct = product
                                val view = ResultsView.of(product)
                                // A vehicle bookmark edits in the structured car form, anything
                                // else in the add/edit sheet.
                                if (view.isCar) openCarEditor(view, product) else showAddSheet = true
                            },
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
            onFreeItems = { showFreeItems = true },
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

    // Car search form
    if (showCarSearch) {
        CarSearchSheet(
            loadModels = { makeId -> client.getCarModels(makeId) },
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
                                platforms = platforms ?: PlatformId.entries,
                            ),
                        ),
                    )
                }
                results = ResultsView(
                    name = name,
                    query = query,
                    platforms = platforms,
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
            // A vehicle search can always reach the form, even with no filters set yet, so they
            // can be added from the results.
            onEditFilters = if (view.isCar) {
                { openCarEditor(view, bookmark) }
            } else null,
            // The price band is part of the saved search, so it only persists once there is one.
            savedMinPrice = bookmark?.searchQuery?.minPrice?.amount?.div(100)?.toFloat(),
            savedMaxPrice = bookmark?.searchQuery?.maxPrice?.amount?.div(100)?.toFloat(),
            onPriceRangePersist = bookmark?.let { saved ->
                { minEur: Int, maxEur: Int ->
                    productViewModel.updateProduct(
                        saved.copy(searchQuery = saved.searchQuery.withPriceRangeEur(minEur, maxEur)),
                    )
                }
            },
            blockedTerms = resultsBlockedTerms,
            onBlockedTermsChange = { updated ->
                resultsBlockedTerms = updated
                bookmark?.let { productViewModel.setBlockedKeywords(it, updated) }
            },
            isBookmarked = bookmark != null,
            onToggleBookmark = {
                if (bookmark != null) {
                    productViewModel.deleteProduct(bookmark.id)
                } else {
                    productViewModel.createProduct(
                        name = view.name.ifBlank { view.query },
                        searchText = view.query,
                        platforms = view.platforms ?: PlatformId.entries,
                        carFilters = view.filters,
                        excludeKeywords = resultsBlockedTerms,
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

// ── Product Card ─────────────────────────────────────────────

@Composable
internal fun ProductCard(
    product: TrackedProduct,
    onDelete: () -> Unit,
    onViewListings: () -> Unit,
    onEdit: () -> Unit,
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
                    color = MaterialTheme.colorScheme.primary,
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
                }

                Spacer(Modifier.width(4.dp))

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
            addAll(editProduct?.searchQuery?.platforms ?: prefill?.effectivePlatforms ?: PlatformId.entries)
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
                Text("Platforms (${selectedPlatforms.size}/${PlatformId.entries.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { selectedPlatforms.clear(); selectedPlatforms.addAll(PlatformId.entries) }) {
                        Text("All", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = { selectedPlatforms.clear() }) {
                        Text("None", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PlatformId.entries.forEach { platform ->
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
