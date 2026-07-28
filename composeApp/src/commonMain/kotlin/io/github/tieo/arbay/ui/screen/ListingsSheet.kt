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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.rememberCoordDetector
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.READABLE_WIDTH
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.PlatformStatus
import io.github.tieo.arbay.model.SortMode
import androidx.compose.ui.geometry.Size
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Active car filters as (label, facet-dimension key) pairs for the editable chip row. The key
 *  matches the server's facet map so each chip can show how many results dropping it would add. */
private val DIM_LABELS = mapOf(
    "year" to "Year", "mileage" to "Mileage", "price" to "Price", "power" to "Power",
    "transmission" to "Gearbox", "fuel" to "Fuel", "bodyType" to "Body", "condition" to "Condition",
    "color" to "Colour", "drivetrain" to "Drivetrain", "doors" to "Doors", "seats" to "Seats",
    "emission" to "Emission", "seller" to "Seller", "vanLength" to "Length", "vanHeight" to "Height",
    "description" to "In description",
)


internal fun normalizeCountry(c: String): String? = when (c.trim().uppercase()) {
    "D", "DE", "DEUTSCHLAND", "GERMANY" -> "DE"
    "A", "AT", "ÖSTERREICH", "OESTERREICH", "AUSTRIA" -> "AT"
    "CH", "SCHWEIZ", "SWITZERLAND", "SUISSE" -> "CH"
    "F", "FR", "FRANKREICH", "FRANCE" -> "FR"
    "I", "IT", "ITALIEN", "ITALY", "ITALIA" -> "IT"
    "E", "ES", "SPANIEN", "SPAIN" -> "ES"
    "NL", "NIEDERLANDE", "NETHERLANDS" -> "NL"
    "B", "BE", "BELGIEN", "BELGIUM" -> "BE"
    "L", "LU", "LUXEMBURG", "LUXEMBOURG" -> "LU"
    "PL", "POLEN", "POLAND" -> "PL"
    "CZ", "TSCHECHIEN", "CZECHIA" -> "CZ"
    "DK", "DÄNEMARK", "DENMARK" -> "DK"
    "SE", "SCHWEDEN", "SWEDEN" -> "SE"
    else -> c.trim().takeIf { it.length == 2 && it.all { ch -> ch.isLetter() } }?.uppercase()
}

@Composable
fun ListingsSheet(
    productName: String,
    searchQuery: String,
    listingViewModel: ListingViewModel,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    onBookmark: (() -> Unit)? = null,
    // Header bookmark toggle: filled when this search is already a saved bookmark, outline when not.
    // Tapping saves or removes it. Preferred over the bottom "Save search" FAB where wired.
    isBookmarked: Boolean = false,
    onToggleBookmark: (() -> Unit)? = null,
    platforms: List<PlatformId>? = null,
    carFilters: CarFilters? = null,
    onEditFilters: (() -> Unit)? = null,
    // The saved price bound from the bookmark's filter (display currency), so the results slider
    // starts where the user last left it instead of resetting to the full range on reopen.
    // The saved search this view is showing, when there is one. Every filter choice made here is
    // written back onto it, so price band, condition and order survive the view closing.
    savedFilters: SearchQuery? = null,
    // Store-only — must NOT trigger a re-crawl. These filters read what was already fetched.
    onFiltersPersist: ((SearchQuery) -> Unit)? = null,
    blockedTerms: List<String> = emptyList(),
    onBlockedTermsChange: ((List<String>) -> Unit)? = null,
) {
    val listings by listingViewModel.listings.collectAsState()
    val facets by listingViewModel.facets.collectAsState()
    val loading by listingViewModel.loading.collectAsState()
    // Feed the bookmark's blocked keywords into the view model so results filter them out.
    LaunchedEffect(blockedTerms) { listingViewModel.setBlockedTerms(blockedTerms) }
    val activeBlockedTerms by listingViewModel.blockedTerms.collectAsState()
    // Block/unblock a word from a listing: filter live (always) and persist onto the bookmark when
    // a change sink is wired. The mutation must run first — folding it into a null-safe call would
    // short-circuit and never block when no sink is attached (the car/preview sheets).
    val blockWord: (String) -> Unit = { w ->
        val updated = listingViewModel.blockTerm(w)
        onBlockedTermsChange?.invoke(updated)
    }
    val unblockWord: (String) -> Unit = { t ->
        val updated = listingViewModel.unblockTerm(t)
        onBlockedTermsChange?.invoke(updated)
    }
    val marketCapabilities by listingViewModel.capabilities.collectAsState()
    val shownMarkets by listingViewModel.shownMarkets.collectAsState()
    val shownCountries by listingViewModel.shownCountries.collectAsState()
    // The market and country narrowing is part of the saved search, like the price band.
    LaunchedEffect(savedFilters) {
        savedFilters?.let {
            listingViewModel.showMarkets(it.showOnlyMarkets)
            listingViewModel.showCountries(it.showOnlyCountries)
        }
    }
    val platformStatuses by listingViewModel.platformStatuses.collectAsState()
    val totalPlatforms by listingViewModel.totalPlatforms.collectAsState()
    val completedPlatforms by listingViewModel.completedPlatforms.collectAsState()
    val priceHistory by listingViewModel.priceHistory.collectAsState()
    val soldLoadingState by listingViewModel.soldLoading.collectAsState()

    val allActiveListings = remember(listings) { listings.filter { !it.sold } }
    // Merge live sold results with persisted history, deduplicate by id, most recent first
    val allSoldListings = remember(listings, priceHistory) {
        val seen = mutableSetOf<String>()
        (listings.filter { it.sold } + priceHistory)
            .filter { seen.add(it.id) }
            .sortedByDescending { it.soldDate ?: it.scrapedAt }
    }

    // Price range slider bounds from ALL active listings, before filtering; uses converted prices.
    // Bounds are the 5th/95th percentile, not the absolute min/max: a lone cheap part or a single
    // dear outlier must not stretch the track so the real cluster is a hair-thin sliver. Listings
    // outside the band still show (the filter only bites once the user narrows inside the band).
    val allActivePrices = remember(allActiveListings) { allActiveListings.map { DisplayCurrency.convert(it.effectivePrice.amount, it.effectivePrice.currency.name) }.sorted() }
    val priceMin = remember(allActivePrices) {
        if (allActivePrices.isEmpty()) 0f
        else {
            val i = if (allActivePrices.size >= 12) (allActivePrices.size * 0.05f).toInt() else 0
            allActivePrices[i] / 100f
        }
    }
    val priceMax = remember(allActivePrices) {
        if (allActivePrices.isEmpty()) 1000f
        else {
            val n = allActivePrices.size
            val i = if (n >= 12) (n - 1 - (n * 0.05f).toInt()).coerceIn(0, n - 1) else n - 1
            (allActivePrices[i] / 100f).coerceAtLeast(priceMin + 1f)
        }
    }
    // Seed from the saved bound (clamped into the current data range), else the full range.
    var priceRange by remember(priceMin, priceMax) {
        val savedLow = savedFilters?.minPrice?.amount?.div(100)?.toFloat()
        val savedHigh = savedFilters?.maxPrice?.amount?.div(100)?.toFloat()
        val lo = savedLow?.coerceIn(priceMin, priceMax) ?: priceMin
        val hi = savedHigh?.coerceIn(priceMin, priceMax)?.coerceAtLeast(lo) ?: priceMax
        mutableStateOf(lo..hi)
    }
    // The condition and the order are part of the saved search, so they open where they were left.
    var conditionFilter by remember(savedFilters) {
        mutableStateOf(
            when {
                savedFilters?.condition?.contains(Condition.NEW) == true -> "NEW"
                savedFilters?.condition?.any { it != Condition.NEW } == true -> "USED"
                else -> null
            },
        )
    }
    var showFilters by remember { mutableStateOf(false) }
    var showPrice by remember { mutableStateOf(false) }
    var showMarkets by remember { mutableStateOf(false) }

    // Nearest-first: fetch the device position and order by the distance measured from the
    // coordinates the server already resolved for each listing. No re-crawl.
    val sortByDistance by listingViewModel.sortByDistance.collectAsState()
    val sortMode by listingViewModel.sortMode.collectAsState()
    LaunchedEffect(savedFilters?.sort) {
        savedFilters?.sort?.let { listingViewModel.setSortMode(it) }
    }
    val detectAndSortNearest = rememberCoordDetector { lat, lon ->
        listingViewModel.setLocation(lat, lon)
        listingViewModel.setSortByDistance(lat != null)
    }

    // Remember the band on the saved search, whether it was set by dragging the slider or typed
    // into the fields — they edit one value, so they save it the same way.
    fun persistFilters(edit: (SearchQuery) -> SearchQuery) {
        val base = savedFilters ?: return
        onFiltersPersist?.invoke(edit(base))
    }

    fun persistPriceRange() {
        persistFilters { it.withPriceRangeEur(priceRange.start.toInt(), priceRange.endInclusive.toInt()) }
    }

    // Apply ALL filters (price + condition + blocked terms already applied by ViewModel)
    val priceFiltered = priceRange.start > priceMin || priceRange.endInclusive < priceMax
    // Bounds compare in whole currency units, and a thumb resting on the track's end means
    // "unbounded". The slider carries a Float of major units while a price is exact Long minor
    // units, so comparing cent-for-cent would shave a cent off a boundary and drop the very
    // listing the user narrowed onto.
    fun inPriceRange(amount: Long): Boolean {
        val units = amount / 100.0
        val minOk = priceRange.start <= priceMin || units >= floor(priceRange.start.toDouble())
        val maxOk = priceRange.endInclusive >= priceMax || units <= ceil(priceRange.endInclusive.toDouble())
        return minOk && maxOk
    }

    val activeListings = remember(allActiveListings, priceRange) {
        if (!priceFiltered) allActiveListings
        else allActiveListings.filter { inPriceRange(DisplayCurrency.convert(it.effectivePrice.amount, it.effectivePrice.currency.name)) }
    }
    val displayedActiveListings = remember(activeListings, conditionFilter) {
        activeListings.filter { conditionMatches(conditionFilter, it.condition) }
    }
    val soldListings = remember(allSoldListings, priceRange) {
        allSoldListings
            .let { if (priceFiltered) it.filter { l -> inPriceRange(DisplayCurrency.convert(l.effectivePrice.amount, l.effectivePrice.currency.name)) } else it }
    }

    // All stats computed from FILTERED data; converted prices make cross-currency listings comparable.
    fun Listing.convertedPrice(): Long = DisplayCurrency.convert(effectivePrice.amount, effectivePrice.currency.name)
    val allPrices = remember(activeListings) { activeListings.map { it.convertedPrice() }.sorted() }
    val displayCur = Currency.valueOf(DisplayCurrency.current)
    val medianPrice = remember(allPrices) { medianMoney(allPrices, displayCur) }
    val minPrice = remember(activeListings) {
        activeListings.minByOrNull { it.convertedPrice() }?.let { Money(it.convertedPrice(), displayCur) }
    }
    // Upper reference for the summary — the 95th percentile, so one outlier doesn't inflate it.
    val maxPrice = remember(allPrices) {
        if (allPrices.isEmpty()) null
        else {
            val i = if (allPrices.size >= 12) (allPrices.size - 1 - (allPrices.size * 0.05f).toInt()).coerceIn(0, allPrices.size - 1) else allPrices.size - 1
            Money(allPrices[i], displayCur)
        }
    }

    val usedListings = remember(activeListings) {
        activeListings.filter { it.condition != null && it.condition != Condition.NEW }
    }
    val newListings = remember(activeListings) {
        activeListings.filter { it.condition == Condition.NEW }
    }
    val minUsedPrice = remember(usedListings) {
        usedListings.minByOrNull { it.convertedPrice() }?.let { Money(it.convertedPrice(), displayCur) }
    }
    val medianUsedPrice = remember(usedListings) { medianMoney(usedListings.map { it.convertedPrice() }, displayCur) }
    val minNewPrice = remember(newListings) {
        newListings.minByOrNull { it.convertedPrice() }?.let { Money(it.convertedPrice(), displayCur) }
    }
    val medianNewPrice = remember(newListings) { medianMoney(newListings.map { it.convertedPrice() }, displayCur) }
    val medianSoldPrice = remember(soldListings) { medianMoney(soldListings.map { it.convertedPrice() }, displayCur) }

    val platformOffers = remember(activeListings) {
        activeListings.groupBy { it.platformId }
            .map { (platform, items) ->
                val sortedByConverted = items.sortedBy { it.convertedPrice() }
                PlatformOffer(
                    platform = platform,
                    count = items.size,
                    minPrice = sortedByConverted.firstOrNull()?.let { Money(it.convertedPrice(), displayCur) },
                    medianPrice = if (sortedByConverted.isEmpty()) null else Money(sortedByConverted[sortedByConverted.size / 2].convertedPrice(), displayCur),
                    bestListing = sortedByConverted.firstOrNull(),
                )
            }
            .sortedBy { it.minPrice?.amount ?: Long.MAX_VALUE }
    }

    val answeredMarkets = platformStatuses.count { it.status == PlatformSearchStatus.DONE }
    val failedMarkets = platformStatuses.count {
        it.status in setOf(
            PlatformSearchStatus.ERROR, PlatformSearchStatus.BLOCKED, PlatformSearchStatus.IP_BLOCKED,
            PlatformSearchStatus.TIMEOUT, PlatformSearchStatus.CAPTCHA,
        )
    }
    val activeFilterCount = listOf(
        priceFiltered,
        conditionFilter != null,
        sortMode != SortMode.BEST_MATCH,
        shownMarkets.isNotEmpty() || shownCountries.isNotEmpty(),
        activeBlockedTerms.isNotEmpty(),
    ).count { it }
    val marketChoices = remember(platformOffers, activeListings) {
        // How far the nearest offer from each market is, so the filter list can be
        // ordered by what is close rather than by what starts with A. Known only
        // once the search carries a location.
        val nearest = activeListings
            .mapNotNull { listing -> listing.distanceKm?.let { listing.platformId to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, distances) -> distances.min() }
        platformOffers.map { offer ->
            MarketChoice(
                platform = offer.platform,
                name = offer.platform.displayName,
                country = MarketSets.countryOf(offer.platform),
                count = offer.count,
                nearestKm = nearest[offer.platform],
            )
        }
    }
    // Only a market that publishes what sold can answer the sold question at all.
    val soldPossible = remember(platforms) {
        (platforms ?: PlatformId.entries).any { it.name.startsWith("EBAY") }
    }

    LaunchedEffect(searchQuery, carFilters) {
        listingViewModel.search(searchQuery, platforms, carFilters)
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
                modifier = Modifier.widthIn(max = READABLE_WIDTH).fillMaxHeight().align(Alignment.TopCenter),
                contentPadding = PaddingValues(
                    bottom = if (onBookmark != null && onToggleBookmark == null) 60.dp else 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // === Header ===
                item("header") {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                productName,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                                    "${displayedActiveListings.size} offers across ${platformOffers.size} markets",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Cross-border markets are searched in their own language. Each term is
                            // tagged with the country it was used in, kept to one line so the header
                            // stays compact; tap to see them all.
                            var translationsExpanded by remember { mutableStateOf(false) }
                            val translations = remember(platformStatuses) {
                                platformStatuses
                                    .mapNotNull { s ->
                                        val q = s.queryUsed?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                        val cc = runCatching { PlatformId.valueOf(s.platformId).country }.getOrNull()
                                        (cc ?: "") to q
                                    }
                                    .distinctBy { it.second }
                            }
                            if (translations.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { translationsExpanded = true },
                                ) {
                                    Icon(
                                        Icons.Outlined.Translate,
                                        null,
                                        modifier = Modifier.size(13.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        "translated for ${translations.size} markets",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (translationsExpanded) {
                                AlertDialog(
                                    onDismissRequest = { translationsExpanded = false },
                                    title = { Text("Searched abroad as", style = MaterialTheme.typography.titleMedium) },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            translations.forEach { (cc, term) ->
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text(
                                                        cc.ifEmpty { "–" },
                                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.primary,
                                                    )
                                                    Text(term, style = MaterialTheme.typography.bodyMedium)
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = { translationsExpanded = false }) { Text("Close") }
                                    },
                                )
                            }
                        }
                        onToggleBookmark?.let { toggle ->
                            IconButton(onClick = toggle) {
                                Icon(
                                    if (isBookmarked) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                                    if (isBookmarked) "Remove bookmark" else "Save as bookmark",
                                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (!loading) {
                            IconButton(
                                onClick = { listingViewModel.refresh(platforms) },
                            ) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    "Re-crawl",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    }
                }

                // === One row of three doors: everything that is not an offer lives behind one
                // of them, so the offers start at the top of the screen. ===
                if (platformStatuses.isNotEmpty() || allActiveListings.isNotEmpty()) {
                    stickyHeader("doors") {
                        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    ResultsDoor(
                                        icon = Icons.Outlined.Tune,
                                        label = "Filters",
                                        detail = if (activeFilterCount > 0) "$activeFilterCount active" else "none",
                                        highlighted = activeFilterCount > 0,
                                        onClick = { showFilters = true },
                                        modifier = Modifier.weight(1f),
                                    )
                                    ResultsDoor(
                                        icon = Icons.Outlined.Sell,
                                        label = "Price",
                                        detail = minPrice?.let { "from ${it.format()}" } ?: "\u2013",
                                        onClick = { showPrice = true },
                                        modifier = Modifier.weight(1f),
                                    )
                                    ResultsDoor(
                                        icon = Icons.Outlined.Storefront,
                                        label = "Markets",
                                        detail = when {
                                            failedMarkets > 0 -> "$answeredMarkets ok, $failedMarkets not"
                                            else -> "$answeredMarkets answered"
                                        },
                                        highlighted = failedMarkets > 0,
                                        onClick = { showMarkets = true },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
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

                // === All listings ===
                // Rendered whenever there are results at all, even if the price range currently
                // admits none: hiding the section would take the slider's own heading with it and
                // leave no way back.
                if (allActiveListings.isNotEmpty()) {
                    item("listings_header") {
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val hidden = allActiveListings.size - displayedActiveListings.size
                            if (hidden > 0) {
                                Text(
                                    "$hidden more hidden by the filters",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (displayedActiveListings.isEmpty()) {
                        item("listings_none_in_range") {
                            Text(
                                "No listings in this price range — widen it above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    val undatedFrom = if (sortMode != SortMode.NEWEST) -1
                    else displayedActiveListings.indexOfFirst { it.listingDate == null }
                    itemsIndexed(displayedActiveListings, key = { _, l -> "active-${l.id}" }) { index, listing ->
                        if (index == undatedFrom && undatedFrom > 0) {
                            Text(
                                "Below: offers from markets that do not publish a date, so they " +
                                    "cannot be ordered by age.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            )
                        }
                        ListingCard(
                            listing = listing,
                            onBan = { listingViewModel.ban(listing) },
                            onBlockWord = blockWord,
                            searchQuery = searchQuery,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 3.dp),
                        )
                    }
                }

            }

            if (onBookmark != null && onToggleBookmark == null) {
                ExtendedFloatingActionButton(
                    onClick = onBookmark,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Outlined.BookmarkAdd, null, modifier = Modifier.size(22.dp)) },
                    text = { Text("Save search", fontWeight = FontWeight.SemiBold) },
                )
            }
        }

        if (showFilters) {
            FiltersSheet(
                priceMin = priceMin,
                priceMax = priceMax,
                priceRange = priceRange,
                onPriceRange = { priceRange = it },
                onPriceCommitted = { persistPriceRange() },
                condition = conditionFilter,
                onCondition = { chosen ->
                    conditionFilter = chosen
                    persistFilters { query ->
                        query.copy(
                            condition = when (chosen) {
                                "NEW" -> listOf(Condition.NEW)
                                "USED" -> listOf(Condition.USED, Condition.REFURBISHED)
                                else -> null
                            },
                        )
                    }
                },
                newCount = newListings.size,
                usedCount = usedListings.size,
                sort = sortMode,
                onSort = { mode ->
                    if (mode == SortMode.NEAREST) detectAndSortNearest() else listingViewModel.setSortMode(mode)
                    persistFilters { it.copy(sort = mode) }
                },
                markets = marketChoices,
                shownMarkets = shownMarkets,
                onShowMarkets = { chosen ->
                    listingViewModel.showMarkets(chosen)
                    persistFilters { it.copy(showOnlyMarkets = chosen) }
                },
                shownCountries = shownCountries,
                onShowCountries = { chosen ->
                    listingViewModel.showCountries(chosen)
                    persistFilters { it.copy(showOnlyCountries = chosen) }
                },
                blockedTerms = activeBlockedTerms,
                onUnblock = unblockWord,
                onBlock = blockWord,
                activeCount = activeFilterCount,
                onClearAll = {
                    priceRange = priceMin..priceMax
                    conditionFilter = null
                    listingViewModel.showMarkets(emptySet())
                    listingViewModel.showCountries(emptySet())
                    listingViewModel.setSortMode(SortMode.BEST_MATCH)
                    activeBlockedTerms.forEach(unblockWord)
                    persistFilters {
                        it.withPriceRangeEur(null, null).copy(
                            condition = null, sort = null,
                            showOnlyMarkets = emptySet(), showOnlyCountries = emptySet(),
                        )
                    }
                },
                hasCarCriteria = carFilters != null,
                onEditCarCriteria = onEditFilters,
                onDismiss = { showFilters = false },
            )
        }

        if (showPrice) {
            PriceSheet(
                minPrice = minPrice,
                medianPrice = medianPrice,
                maxPrice = maxPrice,
                minNewPrice = minNewPrice,
                medianNewPrice = medianNewPrice,
                newCount = newListings.size,
                minUsedPrice = minUsedPrice,
                medianUsedPrice = medianUsedPrice,
                usedCount = usedListings.size,
                conditionFilter = conditionFilter,
                onConditionFilterChange = { conditionFilter = if (conditionFilter == it) null else it },
                newListings = newListings,
                usedListings = usedListings,
                soldListings = soldListings,
                medianSoldPrice = medianSoldPrice,
                soldLoading = soldLoadingState,
                onSearchSold = { listingViewModel.searchSold() },
                soldPossible = soldPossible,
                onBan = { listingViewModel.ban(it) },
                onDismiss = { showPrice = false },
            )
        }

        if (showMarkets) {
            MarketsSheet(
                statuses = platformStatuses,
                offers = platformOffers.associate { it.platform to it.count },
                capabilities = marketCapabilities,
                onSelectMarket = { market ->
                    listingViewModel.showOnly(market)
                    persistFilters { it.copy(showOnlyMarkets = setOf(market), showOnlyCountries = emptySet()) }
                },
                onDismiss = { showMarkets = false },
            )
        }
    }
}

/** One of the three ways off the results canvas, each carrying the summary of what it opens. */
@Composable
private fun ResultsDoor(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    Surface(
        onClick = onClick,
        color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(icon, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// === Data classes ===

internal data class PlatformOffer(
    val platform: PlatformId,
    val count: Int,
    val minPrice: Money?,
    val medianPrice: Money?,
    val bestListing: Listing?,
)

internal fun Money.format(): String {
    // Show the native currency when we can't convert (unknown rate) rather than mislabelling the
    // raw amount as the display currency \u2014 a 169 900 PLN van must not read as "\u20AC169,900".
    val displayCur = if (DisplayCurrency.canConvert(currency.name)) DisplayCurrency.current else currency.name
    val convertedAmount = if (displayCur == currency.name) amount
        else DisplayCurrency.convert(amount, currency.name)
    val symbol = when (displayCur) {
        "EUR" -> "\u20AC"
        "USD" -> "$"
        "CHF" -> "CHF "
        "GBP" -> "\u00A3"
        else -> "$displayCur "
    }
    val whole = convertedAmount / 100
    val cents = convertedAmount % 100
    return if (cents == 0L) "$symbol$whole" else "$symbol$whole.${cents.toString().padStart(2, '0')}"
}
