package io.github.tieo.arbay.ui.screen

import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.ProductIdentifier
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.ui.AdaptiveFormSheet
import io.github.tieo.arbay.ui.LocalDesktopMode
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.ProductViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    productViewModel: ProductViewModel,
    listingViewModel: ListingViewModel,
    client: ArbayClient,
) {
    val products by productViewModel.products.collectAsState()
    val loading by productViewModel.loading.collectAsState()
    val error by productViewModel.error.collectAsState()
    var showDiscovery by remember { mutableStateOf(false) }
    var showAddSheet by remember { mutableStateOf(false) }
    var addSheetPrefill by remember { mutableStateOf<KnownProduct?>(null) }
    var addSheetInitialQuery by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showListings by remember { mutableStateOf(false) }
    var listingsProduct by remember { mutableStateOf<TrackedProduct?>(null) }
    var previewProduct by remember { mutableStateOf<KnownProduct?>(null) }
    var showPreview by remember { mutableStateOf(false) }
    var previewSearchQuery by remember { mutableStateOf("") }
    var previewSearchName by remember { mutableStateOf("") }
    var cameFromDiscovery by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        productViewModel.loadProducts()
    }

    fun openAddSheet(prefill: KnownProduct? = null, initialQuery: String = "") {
        cameFromDiscovery = showDiscovery
        showDiscovery = false
        addSheetPrefill = prefill
        addSheetInitialQuery = if (prefill != null) prefill.searchQuery else initialQuery
        showAddSheet = true
    }

    fun openPreview(product: KnownProduct) {
        cameFromDiscovery = showDiscovery
        showDiscovery = false
        previewProduct = product
        showPreview = true
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
                        showListings -> { showListings = false; true }
                        showPreview -> { showPreview = false; true }
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

            // Product list — takes all available space
            if (loading && products.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (products.isEmpty()) {
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
                            "No products tracked yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(4.dp))
                        val isDesktop = LocalDesktopMode.current
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
                    items(products, key = { it.id }) { product ->
                        ProductCard(
                            product = product,
                            onDelete = { productViewModel.deleteProduct(product.id) },
                            onViewListings = {
                                listingsProduct = product
                                showListings = true
                            },
                        )
                    }
                }
            }

            // Search bar at the bottom — single entry point
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

    // Discovery sheet — the single entry point for adding products
    if (showDiscovery) {
        DiscoverySheet(
            onDismiss = { showDiscovery = false },
            onProductSelected = { product -> openPreview(product) },
            onCustomSearch = { query -> openAddSheet(initialQuery = query) },
            onLiveSearch = { query ->
                cameFromDiscovery = true
                showDiscovery = false
                previewProduct = null
                showPreview = true
                listingsProduct = null
                previewSearchQuery = query
                previewSearchName = query
            },
        )
    }

    // Add product sheet
    if (showAddSheet) {
        AddProductSheet(
            prefill = addSheetPrefill,
            initialQuery = addSheetInitialQuery,
            onDismiss = {
                showAddSheet = false
                addSheetPrefill = null
                cameFromDiscovery = false
            },
            onBack = if (cameFromDiscovery) {
                {
                    showAddSheet = false
                    addSheetPrefill = null
                    cameFromDiscovery = false
                    showDiscovery = true
                }
            } else null,
            onConfirm = { name, query, platforms, identifiers ->
                productViewModel.createProduct(name, query, platforms, identifiers)
                showAddSheet = false
                addSheetPrefill = null
                cameFromDiscovery = false
            },
        )
    }

    // Alerts

    // Settings
    if (showSettings) {
        SettingsSheet(client = client, onDismiss = { showSettings = false })
    }

    // Listings (for tracked products)
    if (showListings && listingsProduct != null) {
        ListingsSheet(
            productName = listingsProduct!!.name,
            searchQuery = listingsProduct!!.searchQuery.text,
            listingViewModel = listingViewModel,
            platforms = listingsProduct!!.searchQuery.platforms,
            onDismiss = {
                showListings = false
                listingsProduct = null
            },
        )
    }

    // Listings preview (before tracking)
    if (showPreview) {
        val name = previewProduct?.displayName ?: previewSearchName
        val query = previewProduct?.searchQuery ?: previewSearchQuery
        ListingsSheet(
            productName = name,
            searchQuery = query,
            listingViewModel = listingViewModel,
            platforms = previewProduct?.effectivePlatforms,
            onDismiss = {
                showPreview = false
                previewProduct = null
                previewSearchQuery = ""
                previewSearchName = ""
                cameFromDiscovery = false
            },
            onBack = if (cameFromDiscovery) {
                {
                    showPreview = false
                    previewProduct = null
                    previewSearchQuery = ""
                    previewSearchName = ""
                    cameFromDiscovery = false
                    showDiscovery = true
                }
            } else null,
            onTrack = {
                val product = previewProduct
                showPreview = false
                previewProduct = null
                if (product != null) {
                    openAddSheet(prefill = product)
                } else {
                    openAddSheet(initialQuery = previewSearchQuery)
                }
                previewSearchQuery = ""
                previewSearchName = ""
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
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                    Text(
                        product.searchQuery.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                ) {
                    Text(
                        "${product.searchQuery.platforms.size} platforms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // GTIN/MPN badges
            val hasIds = product.identifiers.gtins.isNotEmpty() || product.identifiers.mpn != null
            if (hasIds) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (product.identifiers.gtins.isNotEmpty()) {
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text("GTIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                    product.identifiers.mpn?.let {
                        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text("MPN: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))

                if (product.identifiers.gtins.isNotEmpty()) {
                    Text(
                        "GTINs: ${product.identifiers.gtins.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    product.searchQuery.platforms.forEach { platform ->
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                platform.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onViewListings) {
                        Icon(Icons.Outlined.Search, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("View Listings", style = MaterialTheme.typography.labelMedium)
                    }

                    Row {
                        TextButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Remove", style = MaterialTheme.typography.labelMedium)
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
    initialQuery: String = "",
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    onConfirm: (name: String, query: String, platforms: List<PlatformId>, identifiers: ProductIdentifier) -> Unit,
) {
    var query by remember { mutableStateOf(prefill?.searchQuery ?: initialQuery) }
    var name by remember { mutableStateOf(prefill?.displayName ?: initialQuery) }
    // Auto-fill name from query when user hasn't manually edited the name
    var nameManuallyEdited by remember { mutableStateOf(prefill != null) }
    var gtinText by remember { mutableStateOf(prefill?.gtins?.joinToString(", ") ?: "") }
    var mpn by remember { mutableStateOf(prefill?.mpn ?: "") }
    var showIdentifiers by remember { mutableStateOf(prefill != null && (prefill.mpn != null || prefill.gtins.isNotEmpty())) }
    val selectedPlatforms = remember {
        mutableStateListOf<PlatformId>().apply {
            addAll(prefill?.effectivePlatforms ?: PlatformId.entries)
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
                if (prefill != null) "Track ${prefill.displayName}" else "Track Product",
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
                Text("Track")
            }
        }
    }
}
