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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.rememberCoordDetector
import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.debug.DebugSlice
import io.github.tieo.arbay.debug.debugJson
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.READABLE_WIDTH
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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

/** Which of this sheet's own sub-sheets is open and what the shared filters are set to — state
 *  that lives here, not in [ListingViewModel], so the debug dump would otherwise miss it. */
@Serializable
private data class ResultsSheetUiSnapshot(
    val showFilters: Boolean,
    val showPrice: Boolean,
    val showMarkets: Boolean,
    val conditionFilter: String?,
    val priceRangeStart: Float,
    val priceRangeEnd: Float,
)

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
    // Alternate phrasings that count as this same search (a catalogue product's own data, or
    // whatever a bookmark/history entry carries) — sent to the crawl alongside carFilters, never
    // embedded in searchQuery itself.
    aliases: List<String> = emptyList(),
    onEditFilters: (() -> Unit)? = null,
    // Rewrite the plain search term this view is running, whether or not it is saved. Null hides
    // the affordance entirely (the vehicle-search preview edits through the car form instead).
    onEditQuery: ((String) -> Unit)? = null,
    // Add/remove an alternate spelling that also counts as this search — the structured
    // replacement for typing "OR" into the search box, which used to be parsed back out of the
    // text. Shares the same edit-term dialog rather than a second affordance.
    onAliasesChange: ((List<String>) -> Unit)? = null,
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
    val fetchedListings by listingViewModel.fetched.collectAsState()
    val notSearched by listingViewModel.notSearched.collectAsState()
    val marketBasis by listingViewModel.marketBasis.collectAsState()
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
    // The true min and max — not a percentile trim. A trimmed bound looked like an active filter
    // (a "from"/"to" narrower than what was actually there) while doing nothing, since a listing
    // outside a slider bound that was never dragged still showed anyway; the number on screen and
    // what was actually filterable just disagreed. The slider still only filters once it is
    // actually moved inward from these true ends.
    val allActivePrices = remember(allActiveListings) { allActiveListings.map { DisplayCurrency.convert(it.effectivePrice.amount, it.effectivePrice.currency.name) }.sorted() }
    val priceMin = remember(allActivePrices) { (allActivePrices.firstOrNull() ?: 0L) / 100f }
    val priceMax = remember(allActivePrices) {
        ((allActivePrices.lastOrNull() ?: 100_000L) / 100f).coerceAtLeast(priceMin + 1f)
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

    DebugSlice("resultsScreen") {
        debugJson.encodeToString(
            ResultsSheetUiSnapshot(
                showFilters = showFilters,
                showPrice = showPrice,
                showMarkets = showMarkets,
                conditionFilter = conditionFilter,
                priceRangeStart = priceRange.start,
                priceRangeEnd = priceRange.endInclusive,
            ),
        )
    }

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
    // Upper reference for the summary — the true highest price among what's shown, matching
    // minPrice above: whatever number is on screen is an actual listing, not a trimmed estimate.
    val maxPrice = remember(activeListings) {
        activeListings.maxByOrNull { it.convertedPrice() }?.let { Money(it.convertedPrice(), displayCur) }
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

    val nothingToShow = displayedActiveListings.isEmpty() && soldListings.isEmpty()

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
    // Every market this search asked, whatever came back and whatever is picked right now.
    //
    // Built from the offers alone, the list was the markets that happened to find something: a
    // reader could not tell mobile.de had been asked and had nothing from mobile.de never having
    // been asked at all, and the two mean opposite things about the thing being searched for.
    val marketChoices = remember(marketBasis, platformStatuses, platforms, loading, priceRange, conditionFilter) {
        val offered = marketBasis
            .filter { !it.sold }
            .filter { !priceFiltered || inPriceRange(DisplayCurrency.convert(it.effectivePrice.amount, it.effectivePrice.currency.name)) }
            .filter { conditionMatches(conditionFilter, it.condition) }
        val nearest = offered
            .mapNotNull { listing -> listing.distanceKm?.let { listing.platformId to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, distances) -> distances.min() }
        val byMarket = offered.groupBy { it.platformId }
        val asked = platformStatuses.mapNotNull { status ->
            runCatching { PlatformId.valueOf(status.platformId) }.getOrNull()?.let { it to status }
        }
        // The search's own platform list belongs here too. A market that never reported at all is
        // missing from the statuses, and leaving it off the list is what made a search that could
        // not reach mobile.de look like a search that never covered it.
        val everyMarket = (asked.map { it.first } + byMarket.keys + platforms.orEmpty()).distinct()
        val statusOf = asked.toMap()
        everyMarket.map { platform ->
            val items = byMarket[platform].orEmpty()
            MarketChoice(
                platform = platform,
                name = platform.displayName,
                country = MarketSets.countryOf(platform),
                count = items.size,
                nearestKm = nearest[platform],
                emptyBecause = if (items.isNotEmpty()) null else when (statusOf[platform]?.status) {
                    PlatformSearchStatus.DONE -> "nothing there"
                    PlatformSearchStatus.BLOCKED, PlatformSearchStatus.IP_BLOCKED -> "blocked"
                    PlatformSearchStatus.CAPTCHA -> "captcha"
                    PlatformSearchStatus.TIMEOUT -> "timed out"
                    PlatformSearchStatus.ERROR -> "failed"
                    PlatformSearchStatus.SEARCHING, PlatformSearchStatus.PENDING -> "still asking"
                    // No word from it at all: either it has not started yet, or the run ended
                    // without it ever answering.
                    null -> if (loading) "still asking" else "no answer"
                },
            )
        }.sortedWith(compareByDescending<MarketChoice> { it.count }.thenBy { it.name })
    }

    // Only a market that publishes what sold can answer the sold question at all.
    val soldPossible = remember(platforms) {
        (platforms ?: PlatformId.entries).any { it.name.startsWith("EBAY") }
    }

    // Keyed on searchQuery/carFilters/aliases only, same as before — blockedTerms is read at
    // whatever value it holds when one of those actually changes, but blocking/unblocking a word
    // live must not itself trigger a re-crawl: it only re-filters what was already fetched.
    LaunchedEffect(searchQuery, carFilters, aliases) {
        listingViewModel.search(searchQuery, platforms, carFilters, excludeKeywords = blockedTerms, aliases = aliases)
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
                                    "${displayedActiveListings.size} offers across " +
                                        if (platformOffers.size == 1) "1 market" else "${platformOffers.size} markets",
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
                                    // The terms themselves, not their count: an offer in the list
                                    // that the typed query never named is explained by the word
                                    // beside it, and a bad translation is only visible if shown.
                                    val shown = translations.take(2).joinToString(", ") { it.second }
                                    val rest = translations.size - 2
                                    Text(
                                        if (rest > 0) "also searched: $shown +$rest more" else "also searched: $shown",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
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
                        // A vehicle search's own term still edits through the car form (make/model
                        // fields, not free text) — but aliases are orthogonal to that, so this
                        // shows for a car search too whenever onAliasesChange is offered, just
                        // without the Term field.
                        if (onEditQuery != null || onAliasesChange != null) {
                            var showEditQuery by remember { mutableStateOf(false) }
                            var editQueryText by remember(searchQuery) { mutableStateOf(searchQuery) }
                            // Seeded fresh each time the dialog opens, not tied to the aliases
                            // param directly — editing is a draft until Save.
                            val editAliases = remember(showEditQuery) { aliases.toMutableStateList() }
                            var newAliasText by remember(showEditQuery) { mutableStateOf("") }
                            IconButton(onClick = { editQueryText = searchQuery; showEditQuery = true }) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    "Edit search terms",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (showEditQuery) {
                                AlertDialog(
                                    onDismissRequest = { showEditQuery = false },
                                    title = { Text("Edit search terms", style = MaterialTheme.typography.titleMedium) },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            if (onEditQuery != null) {
                                                OutlinedTextField(
                                                    value = editQueryText,
                                                    onValueChange = { editQueryText = it },
                                                    label = { Text("Term") },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth(),
                                                )
                                            }
                                            if (onAliasesChange != null) {
                                                // A listing matching any one of these counts as a match —
                                                // the structured stand-in for "OR" typed into the box.
                                                Text(
                                                    "Also matches any of these",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                editAliases.forEachIndexed { index, alias ->
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        OutlinedTextField(
                                                            value = alias,
                                                            onValueChange = { editAliases[index] = it },
                                                            singleLine = true,
                                                            modifier = Modifier.weight(1f),
                                                        )
                                                        IconButton(onClick = { editAliases.removeAt(index) }) {
                                                            Icon(Icons.Default.Close, "Remove")
                                                        }
                                                    }
                                                }
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    OutlinedTextField(
                                                        value = newAliasText,
                                                        onValueChange = { newAliasText = it },
                                                        placeholder = { Text("Add an alternate spelling") },
                                                        singleLine = true,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                    IconButton(onClick = {
                                                        val t = newAliasText.trim()
                                                        if (t.isNotBlank() && t !in editAliases) editAliases.add(t)
                                                        newAliasText = ""
                                                    }) {
                                                        Icon(Icons.Default.Add, "Add")
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            val t = editQueryText.trim()
                                            if (onEditQuery != null && t.isNotBlank() && t != searchQuery) onEditQuery(t)
                                            val newAliases = editAliases.toList()
                                            if (onAliasesChange != null && newAliases != aliases) onAliasesChange(newAliases)
                                            showEditQuery = false
                                        }) { Text("Save") }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showEditQuery = false }) { Text("Cancel") }
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
                                            answeredMarkets > 0 ->
                                                if (answeredMarkets == 1) "1 answered" else "$answeredMarkets answered"
                                            // Nothing was asked in this session: these listings come
                                            // from the last crawl this search stored.
                                            platformOffers.isNotEmpty() ->
                                                if (platformOffers.size == 1) "1 market, stored"
                                                else "${platformOffers.size} markets, stored"
                                            else -> "none asked"
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

                // Our filters are not the markets' filters, and the difference is worth a number.
                // Three numbers and their grounds, on one line: a paragraph of it pushed the first
                // offer off the screen, which is the one thing this view exists to show.
                item("hidden-count") {
                    val hiddenHere = marketBasis.count { !it.sold } - displayedActiveListings.size
                    val droppedBeforeArrival = platformStatuses.sumOf {
                        (it.rawCount - it.resultCount).coerceAtLeast(0)
                    }
                    val blockedByWords = fetchedListings.size - marketBasis.size
                    val parts = buildList {
                        if (hiddenHere > 0) add(hiddenHere to "price")
                        if (blockedByWords > 0) add(blockedByWords to "words")
                        if (droppedBeforeArrival > 0) add(droppedBeforeArrival to "criteria")
                    }
                    if (parts.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { showFilters = true }
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                Icons.Outlined.FilterAlt,
                                null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                buildAnnotatedString {
                                    append("Hidden by your filters: ")
                                    parts.forEachIndexed { i, (count, why) ->
                                        if (i > 0) append(" · ")
                                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$count") }
                                        append(" $why")
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                notSearched?.let { why ->
                    item("not-searched") {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(why, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "What is below was stored the last time it ran.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    listingViewModel.search(searchQuery, platforms, carFilters, excludeKeywords = blockedTerms, aliases = aliases, force = true)
                                }) { Text("Try again") }
                            }
                        }
                    }
                }

                // Whether the body has anything at all to draw. Keyed on what is actually shown,
                // not on what was fetched: listings that exist but are all filtered out by price or
                // condition left the screen completely blank, saying nothing about why.
                // A wait with nothing on the screen reads as a screen that is finished and empty.
                // What is known while waiting is which markets have answered, so that is what
                // stands here until the first listing arrives.
                if (loading && nothingToShow) {
                    item("loading") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (totalPlatforms > 0) {
                                LinearProgressIndicator(
                                    progress = { completedPlatforms.toFloat() / totalPlatforms.coerceAtLeast(1) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "$completedPlatforms of $totalPlatforms markets have answered",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            } else {
                                CircularProgressIndicator()
                                Text("Asking the markets", style = MaterialTheme.typography.bodyMedium)
                            }
                            val waiting = platformStatuses.filter { it.status == PlatformSearchStatus.SEARCHING }
                            if (waiting.isNotEmpty()) {
                                Text(
                                    "Still out: " + waiting.take(4).joinToString(", ") { it.platformName } +
                                        if (waiting.size > 4) " and ${waiting.size - 4} more" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            if (answeredMarkets > 0) {
                                Text(
                                    "Nothing yet from the $answeredMarkets that have.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // === Empty ===
                if (nothingToShow && !loading) {
                    item("empty") {
                        Box(
                            Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // Nothing on screen has three causes and they lead somewhere different:
                            // our own filters hide everything that came, no market could be asked,
                            // or every market answered and had nothing.
                            val hiddenByUs = fetchedListings.size
                            val nobodyAnswered = hiddenByUs == 0 && failedMarkets > 0 && answeredMarkets == 0
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    when {
                                        hiddenByUs > 0 -> Icons.Outlined.FilterAltOff
                                        nobodyAnswered -> Icons.Outlined.CloudOff
                                        else -> Icons.Outlined.SearchOff
                                    },
                                    null,
                                    modifier = Modifier.size(48.dp),
                                    tint = if (nobodyAnswered) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.outlineVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    when {
                                        hiddenByUs > 0 -> "Your filters hide all $hiddenByUs of them"
                                        nobodyAnswered -> "No market could be asked"
                                        else -> "No listings found"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    when {
                                        hiddenByUs > 0 -> "The markets answered. Widen the price, the markets or the blocked words."
                                        nobodyAnswered -> "All $failedMarkets blocked, timed out or asked for a captcha. This says nothing about whether the thing exists."
                                        else -> "All $answeredMarkets markets answered and none had one. Try other words."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 32.dp),
                                )
                                Spacer(Modifier.height(14.dp))
                                when {
                                    hiddenByUs > 0 -> OutlinedButton(onClick = { showFilters = true }) { Text("Open filters") }
                                    nobodyAnswered -> OutlinedButton(onClick = { showMarkets = true }) { Text("What each market said") }
                                    else -> {}
                                }
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
                            carFilters = carFilters,
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
                shownCountries = shownCountries,
                onOpenMarkets = {
                    showFilters = false
                    showMarkets = true
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
                // What each market has to give, counted before the market picks so a market does
                // not read as empty because another one is picked.
                offers = marketChoices.associate { it.platform to it.count },
                capabilities = marketCapabilities,
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
    return if (cents == 0L) "$symbol${grouped(whole)}"
    else "$symbol${grouped(whole)}.${cents.toString().padStart(2, '0')}"
}

/** Thousands in groups, because a van at 10000 and one at 100000 are one glance apart otherwise. */
private fun grouped(value: Long): String {
    val digits = value.toString()
    val sign = if (digits.startsWith("-")) "-" else ""
    val body = digits.removePrefix("-")
    return sign + body.reversed().chunked(3).joinToString(",").reversed()
}
