package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.PlatformStatus
import androidx.compose.ui.geometry.Size
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListingsSheet(
    productName: String,
    searchQuery: String,
    listingViewModel: ListingViewModel,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    onTrack: (() -> Unit)? = null,
    platforms: List<PlatformId>? = null,
) {
    val listings by listingViewModel.listings.collectAsState()
    val loading by listingViewModel.loading.collectAsState()
    val selectedPlatform by listingViewModel.selectedPlatform.collectAsState()
    val blockedTerms by listingViewModel.blockedTerms.collectAsState()
    val platformStatuses by listingViewModel.platformStatuses.collectAsState()
    val totalPlatforms by listingViewModel.totalPlatforms.collectAsState()
    val completedPlatforms by listingViewModel.completedPlatforms.collectAsState()
    val priceHistory by listingViewModel.priceHistory.collectAsState()
    val soldLoadingState by listingViewModel.soldLoading.collectAsState()

    val allActiveListings = remember(listings) { listings.filter { !it.sold } }
    // Merge live sold results with persisted history, deduplicate by id, most recent first
    val allSoldListings = remember(listings, priceHistory, blockedTerms) {
        val seen = mutableSetOf<String>()
        (listings.filter { it.sold } + priceHistory)
            .filter { seen.add(it.id) }
            .filter { listing ->
                if (blockedTerms.isEmpty()) true
                else {
                    val titleLower = listing.title.lowercase()
                    blockedTerms.none { term -> titleLower.contains(term.lowercase()) }
                }
            }
            .sortedByDescending { it.soldDate ?: it.scrapedAt }
    }

    // Price range slider bounds (from ALL active, before filtering)
    val allActivePrices = remember(allActiveListings) { allActiveListings.map { it.effectivePrice.amount }.sorted() }
    val priceMin = remember(allActivePrices) { if (allActivePrices.isEmpty()) 0f else (allActivePrices.first() / 100f) }
    val priceMax = remember(allActivePrices) { if (allActivePrices.isEmpty()) 1000f else (allActivePrices.last() / 100f).coerceAtLeast(priceMin + 1f) }
    var priceRange by remember(priceMin, priceMax) { mutableStateOf(priceMin..priceMax) }
    var conditionFilter by remember { mutableStateOf<String?>(null) }
    var showSold by remember { mutableStateOf(true) }
    var hideUnknownDates by remember { mutableStateOf(false) }

    // Apply ALL filters (price + condition + blocked terms already applied by ViewModel)
    val priceFiltered = priceRange.start > priceMin || priceRange.endInclusive < priceMax
    fun inPriceRange(amount: Long): Boolean {
        val minCents = (priceRange.start * 100).toLong()
        val maxCents = (priceRange.endInclusive * 100).toLong()
        return amount in minCents..maxCents
    }

    val activeListings = remember(allActiveListings, priceRange) {
        if (!priceFiltered) allActiveListings
        else allActiveListings.filter { inPriceRange(it.effectivePrice.amount) }
    }
    val displayedActiveListings = remember(activeListings, conditionFilter) {
        activeListings.filter { listing ->
            when (conditionFilter) {
                "NEW" -> listing.condition == Condition.NEW
                "USED" -> listing.condition != null && listing.condition != Condition.NEW
                else -> true
            }
        }
    }
    val soldListings = remember(allSoldListings, priceRange, hideUnknownDates) {
        allSoldListings
            .let { if (priceFiltered) it.filter { l -> inPriceRange(l.effectivePrice.amount) } else it }
            .let { if (hideUnknownDates) it.filter { l -> l.soldDate != null } else it }
    }

    // All stats computed from FILTERED data
    val allPrices = remember(activeListings) { activeListings.map { it.effectivePrice.amount }.sorted() }
    val medianPrice = remember(allPrices) {
        if (allPrices.isEmpty()) null
        else Money(allPrices[allPrices.size / 2], activeListings.firstOrNull()?.effectivePrice?.currency ?: Currency.EUR)
    }
    val minPrice = remember(activeListings) { activeListings.minByOrNull { it.effectivePrice.amount }?.effectivePrice }

    val usedListings = remember(activeListings) {
        activeListings.filter { it.condition != null && it.condition != Condition.NEW }
    }
    val newListings = remember(activeListings) {
        activeListings.filter { it.condition == Condition.NEW }
    }
    val minUsedPrice = remember(usedListings) { usedListings.minByOrNull { it.effectivePrice.amount }?.effectivePrice }
    val medianUsedPrice = remember(usedListings) {
        val prices = usedListings.map { it.effectivePrice.amount }.sorted()
        if (prices.isEmpty()) null
        else Money(prices[prices.size / 2], usedListings.first().effectivePrice.currency)
    }
    val minNewPrice = remember(newListings) { newListings.minByOrNull { it.effectivePrice.amount }?.effectivePrice }
    val medianNewPrice = remember(newListings) {
        val prices = newListings.map { it.effectivePrice.amount }.sorted()
        if (prices.isEmpty()) null
        else Money(prices[prices.size / 2], newListings.first().effectivePrice.currency)
    }
    val medianSoldPrice = remember(soldListings) {
        val sorted = soldListings.sortedBy { it.effectivePrice.amount }
        if (sorted.isEmpty()) null else sorted[sorted.size / 2].effectivePrice
    }

    val imageListings = remember(listings) {
        val seen = mutableSetOf<String>()
        listings.flatMap { listing ->
            listing.imageUrls
                .filter { it.startsWith("http") && !it.contains("placeholder") && !it.contains("no-image") }
                .filter { seen.add(it) }
                .map { url -> url to listing }
        }.take(10)
    }

    val platformOffers = remember(activeListings) {
        activeListings.groupBy { it.platformId }
            .map { (platform, items) ->
                PlatformOffer(
                    platform = platform,
                    count = items.size,
                    minPrice = items.minByOrNull { it.effectivePrice.amount }?.effectivePrice,
                    medianPrice = items.sortedBy { it.effectivePrice.amount }.let { sorted ->
                        if (sorted.isEmpty()) null else sorted[sorted.size / 2].effectivePrice
                    },
                    bestListing = items.minByOrNull { it.effectivePrice.amount },
                )
            }
            .sortedBy { it.minPrice?.amount ?: Long.MAX_VALUE }
    }

    LaunchedEffect(searchQuery) {
        listingViewModel.search(searchQuery, platforms)
    }

    AdaptiveSheet(onDismiss = onDismiss) {
        if (onBack != null) {
            BackHandler(onBack = onBack)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = if (onTrack != null) 60.dp else 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // === Product images ===
                if (imageListings.isNotEmpty()) {
                    item("images") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(imageListings) { (url, listing) ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .widthIn(min = 140.dp, max = 240.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .clickable { openBrowser(listing.url) },
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }

                // === Header ===
                item("header") {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                productName,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            if (loading && totalPlatforms > 0) {
                                Text(
                                    "Searching $completedPlatforms/$totalPlatforms platforms\u2026 ${listings.size} results",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else if (listings.isNotEmpty()) {
                                Text(
                                    "${activeListings.size} offers across ${platformOffers.size} platforms",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (!loading) {
                            IconButton(onClick = { listingViewModel.refresh(platforms) }) {
                                Icon(
                                    Icons.Outlined.Refresh, "Re-crawl",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // === Sticky filters (platform chips + blocked terms) ===
                if (platformStatuses.isNotEmpty() || platformOffers.isNotEmpty() || blockedTerms.isNotEmpty()) {
                    stickyHeader("filters") {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column {
                                if (platformStatuses.isNotEmpty() || platformOffers.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    UnifiedPlatformChips(
                                        statuses = platformStatuses,
                                        offers = platformOffers,
                                        selectedPlatform = selectedPlatform,
                                        onSelectPlatform = { listingViewModel.selectPlatform(it) },
                                        modifier = Modifier.padding(horizontal = 20.dp),
                                    )
                                }
                                if (blockedTerms.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(
                                            Icons.Outlined.FilterAlt,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        blockedTerms.sorted().forEach { term ->
                                            InputChip(
                                                selected = true,
                                                onClick = { listingViewModel.unblockTerm(term) },
                                                label = { Text(term, style = MaterialTheme.typography.labelSmall) },
                                                trailingIcon = {
                                                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(14.dp))
                                                },
                                )
                            }
                        }
                                }
                                // Price range slider
                                if (activeListings.size >= 2 && priceMax > priceMin) {
                                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                Money((priceRange.start * 100).toLong(), activeListings.firstOrNull()?.effectivePrice?.currency ?: Currency.EUR).format(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                Money((priceRange.endInclusive * 100).toLong(), activeListings.firstOrNull()?.effectivePrice?.currency ?: Currency.EUR).format(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        RangeSlider(
                                            value = priceRange,
                                            onValueChange = { priceRange = it },
                                            valueRange = priceMin..priceMax,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }

                // === Loading (only when zero results yet) ===
                if (loading && listings.isEmpty() && platformStatuses.isEmpty()) {
                    item("loading") {
                        Box(
                            Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                // === Empty ===
                if (listings.isEmpty() && !loading) {
                    item("empty") {
                        Box(
                            Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.SearchOff, null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.outlineVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("No listings found", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Try a different search or check back later.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // === Price overview ===
                if (listings.isNotEmpty()) {
                    item("prices") {
                        Spacer(Modifier.height(16.dp))
                        PriceOverview(
                            minPrice = minPrice,
                            medianPrice = medianPrice,
                            minNewPrice = minNewPrice,
                            medianNewPrice = medianNewPrice,
                            newCount = newListings.size,
                            minUsedPrice = minUsedPrice,
                            medianUsedPrice = medianUsedPrice,
                            usedCount = usedListings.size,
                            medianSoldPrice = medianSoldPrice,
                            soldCount = soldListings.size,
                            conditionFilter = conditionFilter,
                            onConditionFilterChange = { conditionFilter = if (conditionFilter == it) null else it },
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }

                // === Price chart (distribution with optional sold history) ===
                if (activeListings.size >= 3 || soldListings.isNotEmpty()) {
                    item("price_chart") {
                        Spacer(Modifier.height(12.dp))
                        PriceDistributionChart(
                            newListings = newListings,
                            usedListings = usedListings,
                            soldListings = soldListings,
                            medianNewPrice = medianNewPrice?.amount,
                            medianUsedPrice = medianUsedPrice?.amount,
                            onSearchSold = { listingViewModel.searchSold() },
                            soldLoading = soldLoadingState,
                            onBan = { listingViewModel.ban(it) },
                            onBlockWord = { listingViewModel.blockTerm(it) },
                            searchQuery = searchQuery,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }

                // (Price range slider moved to sticky header)

                // (platform filter chips are now merged into the unified platform chips above)

                // (Platform offers merged into unified platform chips above)

                // === All listings ===
                if (conditionFilter != "SOLD" && activeListings.isNotEmpty()) {
                    item("listings_header") {
                        Spacer(Modifier.height(16.dp))
                        val filterLabel = when {
                            conditionFilter == "NEW" || priceFiltered -> "Filtered listings (${displayedActiveListings.size})"
                            conditionFilter == "USED" -> "Used listings (${displayedActiveListings.size})"
                            else -> "All listings (${activeListings.size})"
                        }
                        Text(
                            filterLabel,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    items(displayedActiveListings, key = { "active-${it.id}" }) { listing ->
                        ListingCard(
                            listing = listing,
                            onBan = { listingViewModel.ban(listing) },
                            onBlockWord = { term -> listingViewModel.blockTerm(term) },
                            searchQuery = searchQuery,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp),
                        )
                    }
                }

                // === Price History ===
                item("sold_header") {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    "Sold (${soldListings.size})",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                )
                                if (soldLoadingState) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                } else {
                                    TextButton(
                                        onClick = { listingViewModel.searchSold() },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    ) {
                                        Text("More", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            if (medianSoldPrice != null) {
                                Text(
                                    "Median ${medianSoldPrice.format()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val unknownCount = allSoldListings.count { it.soldDate == null }
                            if (unknownCount > 0) {
                                FilterChip(
                                    selected = hideUnknownDates,
                                    onClick = { hideUnknownDates = !hideUnknownDates },
                                    label = { Text("Hide unknown", style = MaterialTheme.typography.labelSmall) },
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.height(28.dp),
                                )
                            }
                            if (soldListings.isNotEmpty()) {
                                IconButton(onClick = { showSold = !showSold }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        if (showSold) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null, modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (soldListings.isEmpty()) {
                    item("sold_empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.History, null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.outlineVariant,
                                )
                                Text(
                                    if (loading) "Loading sold history…" else "No sold history found",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    if (showSold) {
                        items(soldListings, key = { "sold-${it.id}" }) { listing ->
                            SoldHistoryRow(
                                listing = listing,
                                onBan = { listingViewModel.ban(listing) },
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            if (onTrack != null) {
                SmallFloatingActionButton(
                    onClick = onTrack,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Outlined.BookmarkAdd, null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// === Data classes ===

private data class PlatformOffer(
    val platform: PlatformId,
    val count: Int,
    val minPrice: Money?,
    val medianPrice: Money?,
    val bestListing: Listing?,
)

// === Price overview (Idealo-style) ===

@Composable
private fun PriceOverview(
    minPrice: Money?,
    medianPrice: Money?,
    minNewPrice: Money?,
    medianNewPrice: Money?,
    newCount: Int,
    minUsedPrice: Money?,
    medianUsedPrice: Money?,
    usedCount: Int,
    medianSoldPrice: Money?,
    soldCount: Int,
    conditionFilter: String?,
    onConditionFilterChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Main price hero
        if (minPrice != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column {
                        Text(
                            "Best price",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                        Text(
                            minPrice.format(),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (medianPrice != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "Median",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                            Text(
                                medianPrice.format(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }

        // Condition breakdown row — tap to filter
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (minNewPrice != null) {
                ConditionPriceCard(
                    label = "New",
                    minPrice = minNewPrice,
                    medianPrice = medianNewPrice,
                    count = newCount,
                    isSelected = conditionFilter == "NEW",
                    onClick = { onConditionFilterChange("NEW") },
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    onColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            if (minUsedPrice != null) {
                ConditionPriceCard(
                    label = "Used",
                    minPrice = minUsedPrice,
                    medianPrice = medianUsedPrice,
                    count = usedCount,
                    isSelected = conditionFilter == "USED",
                    onClick = { onConditionFilterChange("USED") },
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    onColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            if (soldCount > 0 && medianSoldPrice != null) {
                ConditionPriceCard(
                    label = "Sold",
                    minPrice = null,
                    medianPrice = medianSoldPrice,
                    count = soldCount,
                    isSelected = conditionFilter == "SOLD",
                    onClick = { onConditionFilterChange("SOLD") },
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    onColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ConditionPriceCard(
    label: String,
    minPrice: Money?,
    medianPrice: Money?,
    count: Int,
    color: Color,
    onColor: Color,
    isSelected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color,
        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, onColor) else null,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isSelected) Icon(Icons.Default.Check, null, modifier = Modifier.size(10.dp), tint = onColor)
                Text(
                    "$label ($count)",
                    style = MaterialTheme.typography.labelSmall,
                    color = onColor.copy(alpha = 0.7f),
                )
            }
            if (minPrice != null) {
                Text(
                    "from ${minPrice.format()}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = onColor,
                )
            }
            if (medianPrice != null) {
                Text(
                    "\u00F8 ${medianPrice.format()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = onColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// === Platform offer card (like Idealo's shop rows) ===

@Composable
private fun PlatformOfferCard(
    offer: PlatformOffer,
    onSelect: () -> Unit = {},
    isSelected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    offer.platform.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${offer.count} offers",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    offer.minPrice?.format() ?: "",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary,
                )
                if (offer.medianPrice != null && offer.medianPrice != offer.minPrice) {
                    Text(
                        "\u00F8 ${offer.medianPrice.format()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// === Individual listing card ===

@Composable
internal fun ListingCard(
    listing: Listing,
    onBan: (() -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    var showBlockDialog by remember { mutableStateOf(false) }

    Card(
        onClick = { openBrowser(listing.url) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            val firstImage = listing.imageUrls.firstOrNull { it.startsWith("http") }
            if (firstImage != null) {
                AsyncImage(
                    model = firstImage,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            listing.platformId.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    listing.condition?.let {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Text(
                                it.name.lowercase().replaceFirstChar { c -> c.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (listing.sold) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                        ) {
                            Text(
                                "Sold",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    listing.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                listing.location?.let { loc ->
                    val text = loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
                    if (text.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.LocationOn, null,
                                modifier = Modifier.size(11.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(4.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    listing.effectivePrice.format(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (listing.sold) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                )
                // Show shipping breakdown if shipping cost exists
                val shippingCost = listing.shipping?.cost
                val isFreeShipping = listing.shipping?.free == true
                if (shippingCost != null) {
                    Text(
                        "${listing.price.format()} + ${shippingCost.format()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (isFreeShipping && listing.shipping?.cost == null) {
                    Text(
                        "Free shipping",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                listing.oldPrice?.let {
                    Text(
                        it.format(),
                        style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (onBan != null || onBlockWord != null) {
                Column {
                    if (onBan != null) {
                        IconButton(
                            onClick = onBan,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline, null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                    if (onBlockWord != null) {
                        IconButton(
                            onClick = { showBlockDialog = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Block, null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showBlockDialog && onBlockWord != null) {
        BlockTermDialog(
            listingTitle = listing.title,
            searchQuery = searchQuery,
            onBlock = { term ->
                onBlockWord(term)
                showBlockDialog = false
            },
            onDismiss = { showBlockDialog = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockTermDialog(
    listingTitle: String,
    searchQuery: String,
    onBlock: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val queryWords = remember(searchQuery) {
        searchQuery.lowercase().split(Regex("[\\s\\-]+"))
            .filter { it.length > 1 && !it.startsWith("-") && it != "or" }
            .toSet()
    }
    val candidateWords = remember(listingTitle, queryWords) {
        listingTitle.split(Regex("[\\s\\-/|,()\\[\\]]+"))
            .map { it.trim().replace(Regex("[^\\p{L}\\p{N}]"), "") }
            .filter { it.length >= 2 }
            .map { it.lowercase() }
            .distinct()
            .filter { word -> queryWords.none { q -> word.contains(q) || q.contains(word) } }
    }

    var phraseMode by remember { mutableStateOf(false) }
    val selectedWords = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block a word", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                if (phraseMode && selectedWords.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    ) {
                        Text(
                            selectedWords.joinToString(" "),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                Text(
                    if (phraseMode) "Tap words to add to phrase:" else "Tap to block. Long press for phrase:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    candidateWords.forEach { word ->
                        val isSelected = word in selectedWords
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                                .pointerInput(word, phraseMode) {
                                    detectTapGestures(
                                        onLongPress = {
                                            phraseMode = true
                                            selectedWords.clear()
                                            selectedWords.add(word)
                                        },
                                        onTap = {
                                            if (phraseMode) {
                                                if (isSelected) selectedWords.remove(word)
                                                else if (word !in selectedWords) selectedWords.add(word)
                                            } else {
                                                onBlock(word)
                                            }
                                        },
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                word,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (phraseMode && selectedWords.size >= 2) {
                TextButton(onClick = {
                    onBlock(selectedWords.joinToString(" "))
                }) { Text("Block phrase") }
            }
        },
        dismissButton = {
            Row {
                if (phraseMode) {
                    TextButton(onClick = { phraseMode = false; selectedWords.clear() }) { Text("Back") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// === Price Distribution Histogram (active listings — New vs Used bars) ===

@Composable
private fun PriceDistributionChart(
    newListings: List<Listing>,
    usedListings: List<Listing>,
    soldListings: List<Listing> = emptyList(),
    medianNewPrice: Long?,
    medianUsedPrice: Long?,
    onSearchSold: () -> Unit = {},
    soldLoading: Boolean = false,
    onBan: ((Listing) -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    // Internal chart filter — independent of the listing-card filter
    // null = all active, "NEW" = new only, "USED" = used only, "SOLD" = sold history chart
    val defaultFilter = if (newListings.isEmpty() && usedListings.isEmpty() && soldListings.isNotEmpty()) "SOLD" else null
    var chartFilter by remember { mutableStateOf(defaultFilter) }

    if (newListings.isEmpty() && usedListings.isEmpty() && soldListings.isEmpty()) return

    val allActive = newListings + usedListings
    val hasActive = allActive.isNotEmpty()
    val allPrices = allActive.map { it.effectivePrice.amount }
    val minPriceAll = allPrices.minOrNull() ?: 0L
    val maxPriceAll = allPrices.maxOrNull() ?: 0L
    val priceRange = (maxPriceAll - minPriceAll).coerceAtLeast(500L)
    val bucketCount = 9

    data class BucketData(var newCount: Int = 0, var usedCount: Int = 0)
    val buckets = Array(bucketCount) { BucketData() }
    fun bucket(price: Long) = ((price - minPriceAll).toDouble() / priceRange * bucketCount).toInt().coerceIn(0, bucketCount - 1)
    if (hasActive) {
        newListings.forEach { buckets[bucket(it.effectivePrice.amount)].newCount++ }
        usedListings.forEach { buckets[bucket(it.effectivePrice.amount)].usedCount++ }
    }
    val maxBarCount = buckets.maxOf { maxOf(it.newCount, it.usedCount) }.coerceAtLeast(1)

    var tappedBucket by remember { mutableStateOf<Int?>(null) }

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val textMeasurer = rememberTextMeasurer()

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header with internal filter chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Price chart",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (newListings.isNotEmpty()) {
                        FilterChip(
                            selected = chartFilter == "NEW",
                            onClick = { chartFilter = if (chartFilter == "NEW") null else "NEW" },
                            label = { Text("New", style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = primary.copy(alpha = 0.2f),
                                selectedLabelColor = primary,
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(26.dp),
                        )
                    }
                    if (usedListings.isNotEmpty()) {
                        FilterChip(
                            selected = chartFilter == "USED",
                            onClick = { chartFilter = if (chartFilter == "USED") null else "USED" },
                            label = { Text("Used", style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = tertiary.copy(alpha = 0.2f),
                                selectedLabelColor = tertiary,
                            ),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.height(26.dp),
                        )
                    }
                    FilterChip(
                        selected = chartFilter == "SOLD",
                        onClick = {
                            val wasSold = chartFilter == "SOLD"
                            chartFilter = if (wasSold) null else "SOLD"
                            if (!wasSold && soldListings.isEmpty()) onSearchSold()
                        },
                        label = {
                            if (soldLoading) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                    Text("Sold", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Text(
                                    if (soldListings.isNotEmpty()) "Sold (${soldListings.size})" else "Sold",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = onSurface.copy(alpha = 0.15f),
                            selectedLabelColor = onSurface,
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.height(26.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            if (chartFilter == "SOLD") {
                if (soldLoading) {
                    Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (soldListings.isNotEmpty()) {
                    PriceHistoryChart(
                        soldListings = soldListings,
                        onBan = onBan,
                        onBlockWord = onBlockWord,
                        searchQuery = searchQuery,
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onSearchSold) { Text("Search sold listings") }
                    }
                }
            } else {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(buckets) {
                        detectTapGestures { offset ->
                            val padL = 4f; val padR = 4f
                            val chartW = size.width - padL - padR
                            val bw = chartW / bucketCount
                            val tapped = ((offset.x - padL) / bw).toInt().coerceIn(0, bucketCount - 1)
                            tappedBucket = if (tappedBucket == tapped) null else tapped
                        }
                    },
            ) {
                val padL = 4f; val padR = 4f; val labelH = 16f
                val chartW = size.width - padL - padR
                val chartH = size.height - labelH
                val bw = chartW / bucketCount
                val barW = bw * 0.38f   // each bar takes 38% of bucket width
                val barGap = bw * 0.06f  // gap between new and used bars
                val bucketPad = bw * 0.09f

                val newAlpha = if (chartFilter == "USED") 0.2f else 1f
                val usedAlpha = if (chartFilter == "NEW") 0.2f else 1f
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))

                // Draw bars first
                for (i in 0 until bucketCount) {
                    val bucketX = padL + i * bw
                    val newBarH = (buckets[i].newCount.toFloat() / maxBarCount) * chartH
                    val usedBarH = (buckets[i].usedCount.toFloat() / maxBarCount) * chartH

                    if (buckets[i].newCount > 0) {
                        drawRect(
                            primary.copy(alpha = newAlpha),
                            topLeft = Offset(bucketX + bucketPad, chartH - newBarH),
                            size = Size(barW, newBarH),
                        )
                    }
                    if (buckets[i].usedCount > 0) {
                        drawRect(
                            tertiary.copy(alpha = usedAlpha),
                            topLeft = Offset(bucketX + bucketPad + barW + barGap, chartH - usedBarH),
                            size = Size(barW, usedBarH),
                        )
                    }

                    // Tap highlight
                    if (tappedBucket == i) {
                        drawRect(onSurface.copy(alpha = 0.06f), topLeft = Offset(bucketX, 0f), size = Size(bw, chartH))
                    }

                    // X-axis price label every 2nd bucket
                    if (i % 2 == 0 || i == bucketCount - 1) {
                        val price = minPriceAll + (priceRange * i / bucketCount)
                        val labelStr = "\u20AC${price / 100}"
                        val tr = textMeasurer.measure(labelStr, TextStyle(fontSize = 8.sp, color = onSurface.copy(alpha = 0.5f)))
                        val lx = (bucketX + bw / 2 - tr.size.width / 2).coerceIn(0f, size.width - tr.size.width)
                        drawText(tr, topLeft = Offset(lx, size.height - tr.size.height))
                    }
                }

                // Draw median lines ON TOP of bars
                medianNewPrice?.let {
                    if (chartFilter != "USED") {
                        val x = padL + ((it - minPriceAll).toFloat() / priceRange) * chartW
                        drawLine(primary, Offset(x, 0f), Offset(x, chartH), strokeWidth = 2f, pathEffect = dash)
                    }
                }
                medianUsedPrice?.let {
                    if (chartFilter != "NEW") {
                        val x = padL + ((it - minPriceAll).toFloat() / priceRange) * chartW
                        drawLine(tertiary, Offset(x, 0f), Offset(x, chartH), strokeWidth = 2f, pathEffect = dash)
                    }
                }
            }

            // Tapped bucket info
            tappedBucket?.let { b ->
                val priceFrom = minPriceAll + (priceRange * b / bucketCount)
                val priceTo = minPriceAll + (priceRange * (b + 1) / bucketCount)
                val inNew = newListings.count { it.effectivePrice.amount in priceFrom..priceTo }
                val inUsed = usedListings.count { it.effectivePrice.amount in priceFrom..priceTo }
                Spacer(Modifier.height(4.dp))
                Text(
                    "\u20AC${priceFrom / 100}–\u20AC${priceTo / 100}: ${if (inNew > 0) "$inNew new" else ""}${if (inNew > 0 && inUsed > 0) " · " else ""}${if (inUsed > 0) "$inUsed used" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Legend
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (newListings.isNotEmpty() && medianNewPrice != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Canvas(Modifier.size(10.dp, 10.dp)) { drawRect(primary, size = size) }
                        Text("New (${newListings.size})", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.7f))
                        Canvas(Modifier.size(12.dp, 2.dp)) { drawLine(primary, Offset.Zero, Offset(size.width, 0f), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 2f))) }
                        Text("median", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.5f))
                    }
                }
                if (usedListings.isNotEmpty() && medianUsedPrice != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Canvas(Modifier.size(10.dp, 10.dp)) { drawRect(tertiary, size = size) }
                        Text("Used (${usedListings.size})", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.7f))
                        Canvas(Modifier.size(12.dp, 2.dp)) { drawLine(tertiary, Offset.Zero, Offset(size.width, 0f), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 2f))) }
                        Text("median", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.5f))
                    }
                }
            }
            } // else (not SOLD)
        }
    }
}

// === Price History Chart (sold price over time) ===

@Composable
private fun PriceHistoryChart(
    soldListings: List<Listing>,
    onBan: ((Listing) -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    // Use soldDate when available, fall back to scrapedAt for sold listings without a date
    val sorted = remember(soldListings) {
        soldListings.sortedBy { it.soldDate ?: it.scrapedAt }
    }
    if (sorted.isEmpty()) return

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val textMeasurer = rememberTextMeasurer()

    // Use the most common currency for Y-axis labels
    val displayCurrency = remember(sorted) {
        sorted.groupingBy { it.effectivePrice.currency }.eachCount()
            .maxByOrNull { it.value }?.key ?: Currency.EUR
    }
    val currencySymbol = when (displayCurrency) {
        Currency.EUR -> "\u20AC"
        Currency.USD -> "$"
        Currency.CHF -> "CHF "
        Currency.GBP -> "\u00A3"
    }

    val prices = sorted.map { it.effectivePrice.amount }
    val minP = prices.min()
    val maxP = prices.max()
    val priceRange = (maxP - minP).coerceAtLeast(100L)

    val timestamps = sorted.map { (it.soldDate ?: it.scrapedAt).epochSeconds }
    val minT = timestamps.min()
    val maxT = timestamps.max()
    val timeRange = (maxT - minT).coerceAtLeast(1L)

    var tappedIdx by remember { mutableStateOf<Int?>(null) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Price history (${sorted.size} sold)",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .pointerInput(sorted) {
                        detectTapGestures { offset ->
                            val padL = 44f; val padR = 8f; val padT = 4f; val labelH = 18f
                            val chartW = size.width - padL - padR
                            val chartH = size.height - padT - labelH
                            val tapped = sorted.indices.minByOrNull { i ->
                                val tx = padL + ((timestamps[i] - minT).toFloat() / timeRange) * chartW
                                val ty = padT + chartH - ((prices[i] - minP).toFloat() / priceRange) * chartH
                                val dx = offset.x - tx; val dy = offset.y - ty
                                dx * dx + dy * dy
                            }
                            tappedIdx = if (tappedIdx == tapped) null else tapped
                        }
                    },
            ) {
                val padL = 44f; val padR = 8f; val padT = 4f; val labelH = 18f
                val chartW = size.width - padL - padR
                val chartH = size.height - padT - labelH

                fun tx(t: Long) = padL + ((t - minT).toFloat() / timeRange) * chartW
                fun ty(p: Long) = padT + chartH - ((p - minP).toFloat() / priceRange) * chartH

                // Y-axis price labels (use actual currency)
                val yTicks = 4
                for (i in 0..yTicks) {
                    val p = minP + priceRange * i / yTicks
                    val y = ty(p)
                    drawLine(onSurface.copy(alpha = 0.08f), Offset(padL, y), Offset(size.width - padR, y), strokeWidth = 0.5f)
                    val lbl = "$currencySymbol${p / 100}"
                    val tr = textMeasurer.measure(lbl, TextStyle(fontSize = 8.sp, color = onSurface.copy(alpha = 0.5f)))
                    drawText(tr, topLeft = Offset(0f, y - tr.size.height / 2f))
                }

                // X-axis time labels
                val xTicks = 4
                for (i in 0..xTicks) {
                    val t = minT + timeRange * i / xTicks
                    val x = tx(t)
                    val instant = kotlinx.datetime.Instant.fromEpochMilliseconds(t * 1000)
                    val tz = TimeZone.currentSystemDefault()
                    val date = instant.toLocalDateTime(tz)
                    val lbl = "${date.dayOfMonth}.${date.monthNumber}"
                    val tr = textMeasurer.measure(lbl, TextStyle(fontSize = 8.sp, color = onSurface.copy(alpha = 0.5f)))
                    drawText(tr, topLeft = Offset((x - tr.size.width / 2).coerceIn(0f, size.width - tr.size.width), size.height - tr.size.height))
                }

                // LOESS trend line (locally weighted scatterplot smoothing)
                if (sorted.size >= 3) {
                    val xData = timestamps.map { it.toFloat() }
                    val yData = prices.map { it.toFloat() }
                    val n = xData.size
                    val steps = 30
                    val bandwidth = 0.35f
                    val k = maxOf(2, (n * bandwidth).toInt())
                    val xMin = xData.min(); val xMax = xData.max()
                    val xStep = (xMax - xMin) / (steps - 1).coerceAtLeast(1)

                    var prevPoint: Offset? = null
                    for (i in 0 until steps) {
                        val xEval = xMin + xStep * i
                        val distances = FloatArray(n) { j -> kotlin.math.abs(xData[j] - xEval) }
                        val maxDist = distances.copyOf().also { it.sort() }[k - 1].coerceAtLeast(1e-6f)
                        var sw = 0f; var swx = 0f; var swx2 = 0f; var swy = 0f; var swxy = 0f
                        for (j in 0 until n) {
                            val u = distances[j] / maxDist
                            if (u >= 1f) continue
                            val t = 1f - u * u * u; val w = t * t * t
                            val xj = xData[j]; val yj = yData[j]
                            sw += w; swx += w * xj; swx2 += w * xj * xj; swy += w * yj; swxy += w * xj * yj
                        }
                        val det = sw * swx2 - swx * swx
                        val yEval = if (kotlin.math.abs(det) < 1e-10f) {
                            if (sw > 0f) swy / sw else yData[n / 2]
                        } else {
                            val a = (swx2 * swy - swx * swxy) / det
                            val b = (sw * swxy - swx * swy) / det
                            a + b * xEval
                        }
                        val pt = Offset(tx(xEval.toLong()), ty(yEval.toLong()))
                        prevPoint?.let { drawLine(primary.copy(alpha = 0.4f), it, pt, strokeWidth = 2.5f) }
                        prevPoint = pt
                    }
                }

                // Dots at each data point
                sorted.forEachIndexed { i, listing ->
                    val x = tx(timestamps[i])
                    val y = ty(prices[i])
                    val isNew = listing.condition == Condition.NEW || listing.condition == null
                    val color = if (isNew) primary else tertiary
                    val isTapped = tappedIdx == i
                    val radius = if (isTapped) 7f else 4.5f
                    drawCircle(color.copy(alpha = 0.85f), radius, Offset(x, y))
                    if (isTapped) drawCircle(color, 2.5f, Offset(x, y))
                }
            }

            // Tapped dot — show full listing card
            tappedIdx?.let { i ->
                val listing = sorted[i]
                Spacer(Modifier.height(4.dp))
                ListingCard(
                    listing = listing,
                    onBan = onBan?.let { { it(listing) } },
                    onBlockWord = onBlockWord,
                    searchQuery = searchQuery,
                )
            }
        }
    }
}

// === Sold history row (compact timeline entry) ===

@Composable
private fun SoldHistoryRow(
    listing: Listing,
    onBan: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dateStr = remember(listing) {
        val instant = listing.soldDate ?: listing.scrapedAt
        val ms = instant.toEpochMilliseconds()
        val d = java.util.Date(ms)
        @Suppress("SimpleDateFormat")
        java.text.SimpleDateFormat("dd MMM yyyy").format(d)
    }
    Card(
        onClick = { openBrowser(listing.url) },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Price — most prominent
            Text(
                listing.effectivePrice.format(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(min = 64.dp),
            )
            // Platform chip
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    listing.platformId.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            listing.condition?.let {
                Text(
                    it.name.lowercase().replaceFirstChar { c -> c.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Title
            Text(
                listing.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Date
            Text(
                dateStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onBan != null) {
                IconButton(onClick = onBan, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.DeleteOutline, null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

// === Crawler status row ===

/**
 * Unified platform chips — merges crawler status + platform filter into one row.
 * Each chip shows: real-time search state → then becomes a filter when done.
 * Tap a chip with results → filter to that platform.
 * Tap a failed chip → show error detail below.
 * "All" chip always first.
 */
@Composable
private fun UnifiedPlatformChips(
    statuses: List<PlatformStatus>,
    offers: List<PlatformOffer>,
    selectedPlatform: PlatformId?,
    onSelectPlatform: (PlatformId?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expandedError by remember { mutableStateOf<String?>(null) }
    val offerMap = remember(offers) { offers.associateBy { it.platform } }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // "All" chip — only show when we have results
            val totalResults = offers.sumOf { it.count }
            if (totalResults > 0) {
                FilterChip(
                    selected = selectedPlatform == null,
                    onClick = { onSelectPlatform(null); expandedError = null },
                    label = { Text("All ($totalResults)") },
                    leadingIcon = {
                        if (selectedPlatform == null) Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                    },
                    shape = RoundedCornerShape(20.dp),
                )
            }

            // One chip per platform — sorted: results by price first, then loading, then errors
            val sortedStatuses = remember(statuses, offerMap) {
                statuses.sortedWith(compareBy<PlatformStatus> { s ->
                    val pid = try { PlatformId.valueOf(s.platformId) } catch (_: Exception) { null }
                    val offer = pid?.let { offerMap[it] }
                    when {
                        offer != null && offer.count > 0 -> 0
                        s.status == PlatformSearchStatus.SEARCHING -> 1
                        else -> 2
                    }
                }.thenBy { s ->
                    val pid = try { PlatformId.valueOf(s.platformId) } catch (_: Exception) { null }
                    pid?.let { offerMap[it] }?.minPrice?.amount ?: Long.MAX_VALUE
                })
            }
            sortedStatuses.forEach { status ->
                val platformId = try { PlatformId.valueOf(status.platformId) } catch (_: Exception) { null }
                val offer = platformId?.let { offerMap[it] }
                val hasResults = (offer?.count ?: 0) > 0
                val isSelected = platformId != null && selectedPlatform == platformId
                val isCaptcha = status.status == PlatformSearchStatus.CAPTCHA
                val isTimeout = status.status == PlatformSearchStatus.TIMEOUT
                val isIpBlocked = status.status == PlatformSearchStatus.IP_BLOCKED
                val isError = status.status == PlatformSearchStatus.ERROR || status.status == PlatformSearchStatus.BLOCKED || isTimeout || isIpBlocked || isCaptcha

                val chipColors = when {
                    isSelected -> FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    isCaptcha -> FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFFFFF3E0), // warm orange bg
                        labelColor = Color(0xFFE65100),
                        iconColor = Color(0xFFE65100),
                    )
                    isTimeout -> FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFFFFF8E1), // yellow bg
                        labelColor = Color(0xFFF57F17),
                        iconColor = Color(0xFFF57F17),
                    )
                    isIpBlocked -> FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        iconColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    isError -> FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        iconColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    status.status == PlatformSearchStatus.SEARCHING -> FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                    hasResults -> FilterChipDefaults.filterChipColors()
                    else -> FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                FilterChip(
                    selected = isSelected,
                    onClick = {
                        when {
                            isError -> {
                                expandedError = if (expandedError == status.platformId) null else status.platformId
                            }
                            hasResults && platformId != null -> {
                                onSelectPlatform(if (isSelected) null else platformId)
                                expandedError = null
                            }
                        }
                    },
                    label = {
                        val name = status.platformName.ifEmpty { status.platformId }
                        when {
                            hasResults && offer != null -> {
                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Text(name, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold))
                                    Text(
                                        "${offer.minPrice?.format() ?: ""} · ${offer.count}",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            status.status == PlatformSearchStatus.DONE -> Text("$name (0)")
                            else -> Text(name)
                        }
                    },
                    leadingIcon = {
                        when {
                            status.status == PlatformSearchStatus.SEARCHING -> {
                                Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 1.5.dp)
                                    val stageChar = when (status.fetchStage) {
                                        "HTTP" -> "H"
                                        "CurlCffi" -> "C"
                                        "Chromium" -> "B"
                                        "Firefox" -> "F"
                                        else -> ""
                                    }
                                    if (stageChar.isNotEmpty()) {
                                        Text(
                                            stageChar,
                                            style = TextStyle(fontSize = 7.sp, fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            isSelected -> Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                            isCaptcha -> Icon(Icons.Default.Lock, null, modifier = Modifier.size(14.dp))
                            isTimeout -> Icon(Icons.Default.Schedule, null, modifier = Modifier.size(14.dp))
                            isIpBlocked -> Icon(Icons.Default.Block, null, modifier = Modifier.size(14.dp))
                            isError -> Icon(Icons.Default.Warning, null, modifier = Modifier.size(14.dp))
                            hasResults -> Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                            status.status == PlatformSearchStatus.DONE -> Icon(Icons.Outlined.RemoveCircleOutline, null, modifier = Modifier.size(14.dp))
                        }
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = chipColors,
                )
            }
        }

        // Error detail (shown when a failed chip is tapped) — tap to copy
        val expandedStatus = statuses.find { it.platformId == expandedError }
        if (expandedStatus?.error != null) {
            val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
            val errorText = buildString {
                append(expandedStatus.platformName)
                expandedStatus.errorType?.let { append(" $it") }
                expandedStatus.fetchStage?.let { append(" (stage: $it)") }
                append("\n\n")
                append(expandedStatus.error ?: "Unknown error")
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = when (expandedStatus.status) {
                    PlatformSearchStatus.CAPTCHA -> Color(0xFFFFF3E0)
                    PlatformSearchStatus.TIMEOUT -> Color(0xFFFFF8E1)
                    else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                },
                modifier = Modifier.clickable {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(errorText))
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val errorIcon = when (expandedStatus.status) {
                        PlatformSearchStatus.CAPTCHA -> Icons.Default.Lock
                        PlatformSearchStatus.TIMEOUT -> Icons.Default.Schedule
                        PlatformSearchStatus.IP_BLOCKED -> Icons.Default.Block
                        else -> Icons.Default.Warning
                    }
                    Icon(errorIcon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            buildString {
                                append(expandedStatus.platformName)
                                expandedStatus.errorType?.let { append(" · $it") }
                                expandedStatus.fetchStage?.let { append(" (stage: $it)") }
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            expandedStatus.error ?: "Unknown error",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (expandedStatus.status == PlatformSearchStatus.CAPTCHA && expandedStatus.captchaUrl != null) {
                        TextButton(
                            onClick = { openBrowser(expandedStatus.captchaUrl!!) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Text("Solve", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

internal fun Money.format(): String {
    val displayCur = io.github.tieo.arbay.DisplayCurrency.current
    val convertedAmount = io.github.tieo.arbay.DisplayCurrency.convert(amount, currency.name)
    val symbol = when (displayCur) {
        "EUR" -> "\u20AC"
        "USD" -> "$"
        "CHF" -> "CHF "
        "GBP" -> "\u00A3"
        else -> "$displayCur "
    }
    val whole = convertedAmount / 100
    val cents = convertedAmount % 100
    return if (cents == 0L) "$symbol$whole" else "$symbol$whole.%02d".format(cents)
}
