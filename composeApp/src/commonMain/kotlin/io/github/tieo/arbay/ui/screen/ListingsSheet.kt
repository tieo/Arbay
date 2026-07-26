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
import io.github.tieo.arbay.ui.viewmodel.ListingViewModel
import io.github.tieo.arbay.ui.viewmodel.PlatformStatus
import io.github.tieo.arbay.ui.viewmodel.SortMode
import androidx.compose.ui.geometry.Size
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Active car filters as (label, facet-dimension key) pairs for the editable chip row. The key
 *  matches the server's facet map so each chip can show how many results dropping it would add. */
private fun carFilterChips(f: CarFilters): List<Pair<String, String>> = buildList {
    when {
        f.firstRegFromYear != null && f.firstRegToYear != null -> add("${f.firstRegFromYear}–${f.firstRegToYear}" to "year")
        f.firstRegFromYear != null -> add("from ${f.firstRegFromYear}" to "year")
        f.firstRegToYear != null -> add("to ${f.firstRegToYear}" to "year")
    }
    f.maxMileageKm?.let { add("≤${it / 1000}k km" to "mileage") }
    f.minMileageKm?.let { add("≥${it / 1000}k km" to "mileage") }
    f.minPowerKw?.let { add("≥$it kW" to "power") }
    f.maxPowerKw?.let { add("≤$it kW" to "power") }
    f.maxPriceEur?.let { add("≤€${it / 1000}k" to "price") }
    f.minPriceEur?.let { add("≥€${it / 1000}k" to "price") }
    f.transmission?.let { add((if (it == Transmission.AUTOMATIC) "Automatik" else "Schaltgetriebe") to "transmission") }
    if (f.fuels.isNotEmpty()) add(f.fuels.joinToString("/") { it.name.lowercase().replaceFirstChar(Char::uppercase) } to "fuel")
    if (f.bodyTypes.isNotEmpty()) add(f.bodyTypes.joinToString("/") { it.name.lowercase() } to "bodyType")
    if (f.conditions.isNotEmpty()) add(f.conditions.joinToString("/") { it.name.lowercase() } to "condition")
    if (f.colors.isNotEmpty()) add(f.colors.joinToString("/") to "color")
    f.drivetrain?.let { add(it.name to "drivetrain") }
    f.minDoors?.let { add("≥$it doors" to "doors") }
    f.minSeats?.let { add("≥$it seats" to "seats") }
    f.minEmissionEuro?.let { add("≥Euro $it" to "emission") }
    f.sellerType?.let { add((if (it == SellerType.PRIVATE) "Private" else "Dealer") to "seller") }
    if (f.vanLengths.isNotEmpty()) add(f.vanLengths.sorted().joinToString("/") { "L$it" } to "vanLength")
    if (f.vanHeights.isNotEmpty()) add(f.vanHeights.sorted().joinToString("/") { "H$it" } to "vanHeight")
    f.descriptionContains?.takeIf { it.isNotBlank() }?.let { add("“$it”" to "description") }
}

private val DIM_LABELS = mapOf(
    "year" to "Year", "mileage" to "Mileage", "price" to "Price", "power" to "Power",
    "transmission" to "Gearbox", "fuel" to "Fuel", "bodyType" to "Body", "condition" to "Condition",
    "color" to "Colour", "drivetrain" to "Drivetrain", "doors" to "Doors", "seats" to "Seats",
    "emission" to "Emission", "seller" to "Seller", "vanLength" to "Length", "vanHeight" to "Height",
    "description" to "In description",
)


/** Human age of a listing from its posting date ("today", "3 days ago", …); null if in the future
 *  or the date is implausible. */
private fun ageLabel(posted: Instant): String? {
    val days = (Clock.System.now() - posted).inWholeDays
    return when {
        days < 0 -> null
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} wk ago"
        days < 365 -> "${days / 30} mo ago"
        else -> "${days / 365} yr ago"
    }
}

/** The country a listing is sourced from, as ISO-2, for the cross-border origin badge. Prefers the
 *  listing's own location (multi-country platforms like AutoScout24 mix markets), else the platform's
 *  home country. Returns null for the home market (DE), which gets no badge, and for unknown origins. */
private fun originCountry(listing: Listing): String? {
    val iso = listing.location?.country?.let { normalizeCountry(it) } ?: listing.platformId.country
    return iso?.uppercase()?.takeUnless { it == "DE" }
}

/** AutoScout24 single-letter codes and German/English country names → ISO-2. */
private fun normalizeCountry(c: String): String? = when (c.trim().uppercase()) {
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

/** Two-letter ISO country code → its flag emoji (regional-indicator pair). "DK" → 🇩🇰.
 *  Each letter maps to a code point above U+FFFF, so it's emitted as a UTF-16 surrogate pair. */
private fun flagEmoji(cc: String): String {
    if (cc.length != 2) return ""
    return buildString {
        for (c in cc.uppercase()) {
            if (c !in 'A'..'Z') return ""
            val cp = 0x1F1E6 + (c - 'A')
            val offset = cp - 0x10000
            append((0xD800 + (offset shr 10)).toChar())
            append((0xDC00 + (offset and 0x3FF)).toChar())
        }
    }
}

/** Median of price amounts (cents, already in the display currency) as Money; null when empty. */
private fun medianMoney(prices: List<Long>, currency: Currency): Money? {
    if (prices.isEmpty()) return null
    val sorted = prices.sorted()
    return Money(sorted[sorted.size / 2], currency)
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
    savedMinPrice: Float? = null,
    savedMaxPrice: Float? = null,
    // Persist the slider's price range back onto the bookmark. Store-only — must NOT trigger a
    // re-crawl; the slider filters locally, this only remembers the choice.
    onPriceRangePersist: ((minEur: Int, maxEur: Int) -> Unit)? = null,
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
    val selectedPlatform by listingViewModel.selectedPlatform.collectAsState()
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
        val lo = savedMinPrice?.coerceIn(priceMin, priceMax) ?: priceMin
        val hi = savedMaxPrice?.coerceIn(priceMin, priceMax)?.coerceAtLeast(lo) ?: priceMax
        mutableStateOf(lo..hi)
    }
    var conditionFilter by remember { mutableStateOf<String?>(null) }
    var showSold by remember { mutableStateOf(true) }
    var hideUnknownDates by remember { mutableStateOf(false) }

    // Nearest-first: fetch the device position and order by the distance measured from the
    // coordinates the server already resolved for each listing. No re-crawl.
    val sortByDistance by listingViewModel.sortByDistance.collectAsState()
    val sortMode by listingViewModel.sortMode.collectAsState()
    val detectAndSortNearest = rememberCoordDetector { lat, lon ->
        listingViewModel.setLocation(lat, lon)
        listingViewModel.setSortByDistance(lat != null)
    }

    // Remember the band on the saved search, whether it was set by dragging the slider or typed
    // into the fields — they edit one value, so they save it the same way.
    fun persistPriceRange() {
        onPriceRangePersist?.invoke(priceRange.start.toInt(), priceRange.endInclusive.toInt())
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
            .let { if (priceFiltered) it.filter { l -> inPriceRange(DisplayCurrency.convert(l.effectivePrice.amount, l.effectivePrice.currency.name)) } else it }
            .let { if (hideUnknownDates) it.filter { l -> l.soldDate != null } else it }
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
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = if (onBookmark != null && onToggleBookmark == null) 60.dp else 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                // === Product images (skipped for car search, where the hero gallery is noise) ===
                if (imageListings.isNotEmpty() && carFilters == null) {
                    item("images") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(imageListings) { (url, listing) ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = listing.title,
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

                // === Sticky filters: platform chips, the car filter chips, and the blocked words —
                // these stay pinned while the results scroll. Price is NOT here; it scrolls away below.
                if (platformStatuses.isNotEmpty() || platformOffers.isNotEmpty()) {
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
                                // Active car filters as editable chips + an "Edit" entry to reopen the
                                // form. "−N" = cars this filter removes (known after the broad fetch).
                                if (carFilters != null && onEditFilters != null) {
                                    val chips = carFilterChips(carFilters)
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        item {
                                            AssistChip(
                                                onClick = onEditFilters,
                                                label = { Text("Filters", style = MaterialTheme.typography.labelMedium) },
                                                leadingIcon = { Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(16.dp)) },
                                            )
                                        }
                                        items(chips) { (label, key) ->
                                            val hidden = facets[key] ?: 0
                                            AssistChip(
                                                onClick = onEditFilters,
                                                label = {
                                                    Text(
                                                        if (hidden > 0) "$label  −$hidden" else label,
                                                        style = MaterialTheme.typography.labelMedium,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                                // Currently blocked words as removable chips (added from a listing's
                                // Block button, per item).
                                if (activeBlockedTerms.isNotEmpty()) {
                                    LazyRow(
                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        // A row item is only as tall as itself, so the leading icon
                                        // needs the row to centre it against the taller chips.
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        item {
                                            Icon(
                                                Icons.Outlined.Block, null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        items(activeBlockedTerms.sorted()) { term ->
                                            InputChip(
                                                selected = false,
                                                onClick = { unblockWord(term) },
                                                label = { Text(term, style = MaterialTheme.typography.labelSmall) },
                                                trailingIcon = { Icon(Icons.Default.Close, "Unblock", modifier = Modifier.size(14.dp)) },
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }

                // === Price filter — scrolls with the content, not pinned. ===
                item("price") {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Column {
                                // Price range: a LOG-scale slider (so a cheap sub-range like 300 to
                                // 700 euro is not a hair-thin sliver of a 0 to 10k track) plus exact
                                // numeric fields. Value shown live while dragging.
                                if (allActiveListings.size >= 2 && priceMax > priceMin) {
                                    val logLo = ln(priceMin.coerceAtLeast(1f).toDouble())
                                    val logHi = ln(priceMax.toDouble()).coerceAtLeast(logLo + 0.0001)
                                    fun priceToPos(p: Float): Float =
                                        ((ln(p.coerceIn(priceMin, priceMax).coerceAtLeast(1f).toDouble()) - logLo) / (logHi - logLo)).toFloat().coerceIn(0f, 1f)
                                    fun posToPrice(pos: Float): Float =
                                        exp(logLo + pos.coerceIn(0f, 1f) * (logHi - logLo)).toFloat().coerceIn(priceMin, priceMax)
                                    var minText by remember(priceRange.start) { mutableStateOf(priceRange.start.toInt().toString()) }
                                    var maxText by remember(priceRange.endInclusive) { mutableStateOf(priceRange.endInclusive.toInt().toString()) }
                                    // Slider values are in the display currency (prices are converted),
                                    // so label it that way, not with some listing's native currency.
                                    val cur = displayCur
                                    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                        RangeSlider(
                                            value = priceToPos(priceRange.start)..priceToPos(priceRange.endInclusive),
                                            onValueChange = { pos ->
                                                val lo = posToPrice(pos.start)
                                                val hi = posToPrice(pos.endInclusive)
                                                priceRange = lo..hi.coerceAtLeast(lo)
                                            },
                                            // Persist the chosen range onto the bookmark when the drag
                                            // ends — store-only, no re-crawl.
                                            onValueChangeFinished = { persistPriceRange() },
                                            valueRange = 0f..1f,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            OutlinedTextField(
                                                value = minText,
                                                onValueChange = { s ->
                                                    minText = s.filter { it.isDigit() }
                                                    minText.toFloatOrNull()?.let { v ->
                                                        priceRange = v.coerceIn(priceMin, priceRange.endInclusive)..priceRange.endInclusive
                                                        persistPriceRange()
                                                    }
                                                },
                                                label = { Text("min ${cur.name}", style = MaterialTheme.typography.labelSmall) },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodySmall,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.weight(1f),
                                            )
                                            OutlinedTextField(
                                                value = maxText,
                                                onValueChange = { s ->
                                                    maxText = s.filter { it.isDigit() }
                                                    maxText.toFloatOrNull()?.let { v ->
                                                        priceRange = priceRange.start..v.coerceIn(priceRange.start, priceMax)
                                                        persistPriceRange()
                                                    }
                                                },
                                                label = { Text("max ${cur.name}", style = MaterialTheme.typography.labelSmall) },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodySmall,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
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

                // === Price overview (skipped for a car search with a single hit, the card says it) ===
                if (listings.isNotEmpty() && !(carFilters != null && activeListings.size <= 1)) {
                    item("prices") {
                        Spacer(Modifier.height(16.dp))
                        PriceOverview(
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
                            conditionFilter = conditionFilter,
                            onSearchSold = { listingViewModel.searchSold() },
                            soldLoading = soldLoadingState,
                            onBan = { listingViewModel.ban(it) },
                            onBlockWord = blockWord,
                            searchQuery = searchQuery,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }

                // === All listings ===
                // Rendered whenever there are results at all, even if the price range currently
                // admits none: hiding the section would take the slider's own heading with it and
                // leave no way back.
                if (allActiveListings.isNotEmpty()) {
                    item("listings_header") {
                        Spacer(Modifier.height(16.dp))
                        val filterLabel = when {
                            conditionFilter == "NEW" || priceFiltered -> "Filtered listings (${displayedActiveListings.size})"
                            conditionFilter == "USED" -> "Used listings (${displayedActiveListings.size})"
                            else -> "All listings (${activeListings.size})"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                filterLabel,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f),
                            )
                            // Sort menu: the order is an explicit choice, not a single toggle.
                            // Nearest needs the device position, so it routes through the detector.
                            var sortMenuOpen by remember { mutableStateOf(false) }
                            Box {
                                FilterChip(
                                    selected = sortMode != SortMode.BEST_MATCH,
                                    onClick = { sortMenuOpen = true },
                                    label = { Text(sortMode.label, style = MaterialTheme.typography.labelSmall) },
                                    leadingIcon = { Icon(Icons.Outlined.SwapVert, null, Modifier.size(14.dp)) },
                                )
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false },
                                ) {
                                    SortMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = { Text(mode.label) },
                                            onClick = {
                                                sortMenuOpen = false
                                                if (mode == SortMode.NEAREST) detectAndSortNearest()
                                                else listingViewModel.setSortMode(mode)
                                            },
                                            leadingIcon = {
                                                if (mode == sortMode) {
                                                    Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                                                }
                                            },
                                        )
                                    }
                                }
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
                    items(displayedActiveListings, key = { "active-${it.id}" }) { listing ->
                        ListingCard(
                            listing = listing,
                            onBan = { listingViewModel.ban(listing) },
                            onBlockWord = blockWord,
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
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    ) {
                                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Load sold", style = MaterialTheme.typography.labelSmall)
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
                                        if (showSold) "Collapse sold list" else "Expand sold list",
                                        modifier = Modifier.size(18.dp),
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
                                onBlockWord = blockWord,
                                searchQuery = searchQuery,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                            )
                        }
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
internal fun PriceOverview(
    minPrice: Money?,
    medianPrice: Money?,
    minNewPrice: Money?,
    medianNewPrice: Money?,
    newCount: Int,
    minUsedPrice: Money?,
    medianUsedPrice: Money?,
    usedCount: Int,
    maxPrice: Money? = null,
    conditionFilter: String?,
    onConditionFilterChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // One restrained summary: the best price is the single loud number (accent); the median and
        // upper bound sit beside it as quiet reference. No competing colour blocks, one statistic
        // per figure (median only — never a mean next to it).
        if (minPrice != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Best price",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            minPrice.format(),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (medianPrice != null) {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "Median ${medianPrice.format()}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (maxPrice != null) {
                                Text(
                                    "up to ${maxPrice.format()}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        // New / Used as compact, neutral filter stats — tap to narrow the list to that condition.
        // Sold is not a stock condition, so it lives on the price chart, not here.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (minNewPrice != null) {
                ConditionStat("New", minNewPrice, newCount, conditionFilter == "NEW",
                    { onConditionFilterChange("NEW") }, Modifier.weight(1f))
            }
            if (minUsedPrice != null) {
                ConditionStat("Used", minUsedPrice, usedCount, conditionFilter == "USED",
                    { onConditionFilterChange("USED") }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ConditionStat(
    label: String,
    from: Money,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, accent) else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "from ${from.format()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// === Individual listing card ===

/** Key vehicle specs under a car listing's title. A verified value (from the site's structured
 *  data) is shown solid; an inferred one (guessed from text) is muted and prefixed "~" so the
 *  user can tell confirmed specs from guesses. */
@Composable
private fun VehicleSpecsRow(v: VehicleInfo) {
    data class Spec(val text: String, val field: VehicleField)
    val specs = buildList {
        v.firstRegYear?.let {
            val ym = if (v.firstRegMonth != null) "%02d/%d".format(v.firstRegMonth, it) else it.toString()
            add(Spec(ym, VehicleField.FIRST_REG_YEAR))
        }
        v.mileageKm?.let { add(Spec("${"%,d".format(it)} km", VehicleField.MILEAGE)) }
        v.powerKw?.let { add(Spec("$it kW", VehicleField.POWER)) }
        v.gearbox?.let {
            val g = if (it == Transmission.AUTOMATIC) "Automatik" else "Schaltgetriebe"
            add(Spec(g, VehicleField.GEARBOX))
        }
        v.fuel?.let { add(Spec(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }, VehicleField.FUEL)) }
        // Van size code: verified when the listing stated an explicit L/H, inferred from a
        // roof/wheelbase word otherwise. Uses the length field's verification for the marker.
        val vanCode = buildString {
            v.vanLength?.let { append("L$it") }
            v.vanHeight?.let { append("H$it") }
        }
        if (vanCode.isNotEmpty()) {
            val field = if (v.vanLength != null) VehicleField.VAN_LENGTH else VehicleField.VAN_HEIGHT
            add(Spec(vanCode, field))
        }
    }
    if (specs.isEmpty()) return
    Spacer(Modifier.height(3.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        specs.take(5).forEach { spec ->
            val verified = v.isVerified(spec.field)
            Text(
                if (verified) spec.text else "~${spec.text}",
                style = MaterialTheme.typography.labelSmall,
                color = if (verified) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ListingCard(
    listing: Listing,
    onBan: (() -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    var showBlockDialog by remember { mutableStateOf(false) }

    // A flat, tappable row on the sheet surface, separated by a hairline divider — not a filled card
    // per item, which reads as clutter across a long list. The price is the strongest element.
    Surface(
        onClick = { openBrowser(listing.url) },
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
      Column {
        Row(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            val firstImage = listing.imageUrls.firstOrNull { it.startsWith("http") }
            if (firstImage != null) {
                AsyncImage(
                    model = firstImage,
                    contentDescription = listing.title,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                // One neutral source tag (platform + optional origin flag). Condition and location
                // are quiet metadata below the title, not more coloured pills.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val origin = originCountry(listing)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            if (origin != null) "${listing.platformId.displayName} ${flagEmoji(origin)}"
                            else listing.platformId.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    if (listing.sold) {
                        Text(
                            "Sold",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.error,
                        )
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
                // Condition as quiet metadata, not a coloured pill.
                listing.condition?.takeIf { !listing.sold }?.let {
                    Text(
                        it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.replace("_", " "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                listing.vehicle?.let { VehicleSpecsRow(it) }

                // Semantic fit to the searcher's ideal-car description, when they gave one.
                listing.matchScore?.let { score ->
                    Spacer(Modifier.height(3.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            "${(score * 100).roundToInt()}% match",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }

                listing.location?.let { loc ->
                    val locText = loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
                    // Distance from the searcher, when the search carried the device position.
                    val distText = listing.distanceKm?.let { "${it.roundToInt()} km away" }
                    val text = listOfNotNull(locText.takeIf { it.isNotBlank() }, distText).joinToString(" · ")
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
                // How old the ad is, when the platform exposes a posting date.
                listing.listingDate?.let { posted ->
                    ageLabel(posted)?.let { label ->
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Schedule, null,
                                modifier = Modifier.size(11.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                // Shipping breakdown when a cost is known, otherwise a free-shipping note.
                val shippingCost = listing.shipping?.cost
                val isFreeShipping = listing.shipping?.free == true
                if (shippingCost != null) {
                    Text(
                        "${listing.price.format()} + ${shippingCost.format()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (isFreeShipping) {
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
                                Icons.Outlined.DeleteOutline, "Hide this listing",
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
                                Icons.Outlined.Block, "Block a word from this listing",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            thickness = 1.dp,
        )
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
    // The query's own words are not offered as block candidates; blocking "crafter" would empty the list.
    val queryWords = remember(searchQuery) {
        searchQuery.lowercase().split(Regex("[\\s\\-]+"))
            .filter { it.length > 1 && !it.startsWith("-") && it != "or" }
            .toSet()
    }
    val candidateWords = remember(listingTitle, queryWords) {
        // Split on every run of non-letters, so punctuation separates words instead of vanishing
        // between them: "OVP!Lagerverkauf" is two words, not one unblockable "ovplagerverkauf".
        listingTitle.split(Regex("[^\\p{L}\\p{N}]+"))
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

// === Price distribution histogram (active listings, New vs Used bars) ===

@Composable
internal fun PriceDistributionChart(
    newListings: List<Listing>,
    usedListings: List<Listing>,
    soldListings: List<Listing> = emptyList(),
    medianNewPrice: Long?,
    medianUsedPrice: Long?,
    // The one shared New/Used selection (null = both); the chart dims to match the listing filter.
    conditionFilter: String? = null,
    onSearchSold: () -> Unit = {},
    soldLoading: Boolean = false,
    onBan: ((Listing) -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    // New/Used comes from the one condition filter shared with the listing cards, so the chart and
    // the list never disagree. Sold is not a condition a listing is in — it is price history, so it
    // is the chart's own mode and nothing else responds to it.
    var showSoldHistory by remember {
        mutableStateOf(newListings.isEmpty() && usedListings.isEmpty() && soldListings.isNotEmpty())
    }
    // Distribution render style, user-switchable: discrete bars or a continuous line over buckets.
    var chartStyle by remember { mutableStateOf("BAR") }

    if (newListings.isEmpty() && usedListings.isEmpty() && soldListings.isEmpty()) return

    val allActive = newListings + usedListings
    val hasActive = allActive.isNotEmpty()
    val allPrices = allActive.map { it.effectivePrice.amount }.sorted()
    // Axis bounds are the 5th/95th percentile, not the extremes: a single dear outlier otherwise
    // stretches the axis so every real listing collapses into one spike at the left. Prices outside
    // the band still count — bucket() clamps them into the first/last bucket.
    val minPriceAll = when {
        allPrices.isEmpty() -> 0L
        allPrices.size >= 12 -> allPrices[(allPrices.size * 0.05f).toInt()]
        else -> allPrices.first()
    }
    val maxPriceAll = when {
        allPrices.isEmpty() -> 0L
        allPrices.size >= 12 -> allPrices[(allPrices.size - 1 - (allPrices.size * 0.05f).toInt()).coerceIn(0, allPrices.size - 1)]
        else -> allPrices.last()
    }
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Bar/line switch, only meaningful for the active distribution (not the sold view).
                    if (!showSoldHistory) {
                        IconButton(
                            onClick = { chartStyle = if (chartStyle == "BAR") "LINE" else "BAR" },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                if (chartStyle == "BAR") Icons.Outlined.ShowChart else Icons.Outlined.BarChart,
                                contentDescription = if (chartStyle == "BAR") "Switch to line" else "Switch to bars",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FilterChip(
                        selected = showSoldHistory,
                        onClick = {
                            val wasSold = showSoldHistory
                            showSoldHistory = !wasSold
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
                                    if (soldListings.isNotEmpty()) "Sold history (${soldListings.size})" else "Sold history",
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

            if (showSoldHistory) {
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

                val newAlpha = if (conditionFilter == "USED") 0.2f else 1f
                val usedAlpha = if (conditionFilter == "NEW") 0.2f else 1f
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))

                // Draw bars first
                for (i in 0 until bucketCount) {
                    val bucketX = padL + i * bw
                    val newBarH = (buckets[i].newCount.toFloat() / maxBarCount) * chartH
                    val usedBarH = (buckets[i].usedCount.toFloat() / maxBarCount) * chartH

                    if (chartStyle == "BAR") {
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

                // Continuous mode: a smoothed line over the per-bucket counts, filled to the axis,
                // one series each for new and used (dimmed by the active New/Used filter, as the bars).
                if (chartStyle == "LINE") {
                    fun seriesLine(count: (Int) -> Int, color: Color, alpha: Float) {
                        if ((0 until bucketCount).all { count(it) == 0 }) return
                        val pts = (0 until bucketCount).map { i ->
                            Offset(padL + i * bw + bw / 2f, chartH - (count(i).toFloat() / maxBarCount) * chartH)
                        }
                        val stroke = Path().apply {
                            moveTo(pts.first().x, pts.first().y)
                            for (i in 1 until pts.size) {
                                val midX = (pts[i - 1].x + pts[i].x) / 2f
                                cubicTo(midX, pts[i - 1].y, midX, pts[i].y, pts[i].x, pts[i].y)
                            }
                        }
                        val area = Path().apply {
                            addPath(stroke)
                            lineTo(pts.last().x, chartH)
                            lineTo(pts.first().x, chartH)
                            close()
                        }
                        drawPath(area, color.copy(alpha = alpha * 0.15f))
                        drawPath(stroke, color.copy(alpha = alpha), style = Stroke(width = 3f))
                    }
                    seriesLine({ buckets[it].newCount }, primary, newAlpha)
                    seriesLine({ buckets[it].usedCount }, tertiary, usedAlpha)
                }

                // Draw median lines ON TOP of bars
                medianNewPrice?.let {
                    if (conditionFilter != "USED") {
                        val x = padL + ((it - minPriceAll).toFloat() / priceRange) * chartW
                        drawLine(primary, Offset(x, 0f), Offset(x, chartH), strokeWidth = 2f, pathEffect = dash)
                    }
                }
                medianUsedPrice?.let {
                    if (conditionFilter != "NEW") {
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

            // Legend: one swatch + count per series, and the dashed marker explained once.
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (newListings.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(9.dp, 9.dp)) { drawRoundRect(primary, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)) }
                        Text("New (${newListings.size})", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.7f))
                    }
                }
                if (usedListings.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(9.dp, 9.dp)) { drawRoundRect(tertiary, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)) }
                        Text("Used (${usedListings.size})", style = MaterialTheme.typography.labelSmall, color = onSurface.copy(alpha = 0.7f))
                    }
                }
                if (medianNewPrice != null || medianUsedPrice != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Canvas(Modifier.size(14.dp, 2.dp)) { drawLine(onSurface.copy(alpha = 0.5f), Offset.Zero, Offset(size.width, 0f), strokeWidth = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 2f))) }
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
internal fun PriceHistoryChart(
    soldListings: List<Listing>,
    onBan: ((Listing) -> Unit)? = null,
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    if (soldListings.isEmpty()) return

    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val textMeasurer = rememberTextMeasurer()

    // Every price is converted to the user's display currency, so cross-border results
    // (EUR/PLN/DKK/SEK/CZK) sit on one comparable axis.
    val displayCurrency = Currency.valueOf(DisplayCurrency.current)
    val currencySymbol = when (displayCurrency) {
        Currency.EUR -> "\u20AC"
        Currency.USD -> "$"
        Currency.CHF -> "CHF "
        Currency.GBP -> "\u00A3"
        Currency.PLN -> "z\u0142"
        Currency.CZK -> "K\u010D"
        Currency.DKK, Currency.SEK, Currency.NOK -> "kr"
        Currency.RSD -> "din"
    }
    fun Listing.priceIn() = DisplayCurrency.convert(effectivePrice.amount, effectivePrice.currency.name)

    // A time axis is only honest for listings that carry a real sold date. When too few do,
    // fall back to a price distribution (points ranked by price) rather than faking dates.
    val dated = remember(soldListings) { soldListings.filter { it.soldDate != null }.sortedBy { it.soldDate } }
    val timeMode = dated.size >= 3
    val points = remember(soldListings, timeMode) {
        if (timeMode) dated else soldListings.sortedBy { it.priceIn() }
    }
    val undatedCount = soldListings.size - dated.size

    val prices = points.map { it.priceIn() }
    val minP = prices.min()
    val maxP = prices.max()
    val priceRange = (maxP - minP).coerceAtLeast(100L)

    val timestamps = points.map { (it.soldDate ?: it.scrapedAt).epochSeconds }
    val minT = timestamps.min()
    val maxT = timestamps.max()
    val timeRange = (maxT - minT).coerceAtLeast(1L)
    fun xFrac(i: Int): Float =
        if (timeMode) (timestamps[i] - minT).toFloat() / timeRange
        else if (points.size == 1) 0.5f else i.toFloat() / (points.size - 1)

    var tappedIdx by remember { mutableStateOf<Int?>(null) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                if (timeMode) "Sold price over time (${points.size})"
                else "Sold price distribution (${points.size})",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            if (undatedCount > 0) {
                Text(
                    if (timeMode) "$undatedCount more sold without a date"
                    else "Dates unavailable — showing price spread",
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(6.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .pointerInput(points) {
                        detectTapGestures { offset ->
                            val padL = 48f; val padR = 8f; val padT = 4f; val labelH = 18f
                            val chartW = size.width - padL - padR
                            val chartH = size.height - padT - labelH
                            val tapped = points.indices.minByOrNull { i ->
                                val px = padL + xFrac(i) * chartW
                                val py = padT + chartH - ((prices[i] - minP).toFloat() / priceRange) * chartH
                                val dx = offset.x - px; val dy = offset.y - py
                                dx * dx + dy * dy
                            }
                            tappedIdx = if (tappedIdx == tapped) null else tapped
                        }
                    },
            ) {
                val padL = 48f; val padR = 8f; val padT = 4f; val labelH = 18f
                val chartW = size.width - padL - padR
                val chartH = size.height - padT - labelH

                fun px(i: Int) = padL + xFrac(i) * chartW
                fun py(p: Long) = padT + chartH - ((p - minP).toFloat() / priceRange) * chartH

                // Y-axis price labels in the display currency
                val yTicks = 4
                for (i in 0..yTicks) {
                    val p = minP + priceRange * i / yTicks
                    val y = py(p)
                    drawLine(onSurface.copy(alpha = 0.08f), Offset(padL, y), Offset(size.width - padR, y), strokeWidth = 0.5f)
                    val lbl = "$currencySymbol${p / 100}"
                    val tr = textMeasurer.measure(lbl, TextStyle(fontSize = 8.sp, color = onSurface.copy(alpha = 0.5f)))
                    drawText(tr, topLeft = Offset(0f, y - tr.size.height / 2f))
                }

                // X-axis date labels only in time mode
                if (timeMode) {
                    val xTicks = 4
                    for (i in 0..xTicks) {
                        val t = minT + timeRange * i / xTicks
                        val x = padL + ((t - minT).toFloat() / timeRange) * chartW
                        val date = Instant.fromEpochSeconds(t).toLocalDateTime(TimeZone.currentSystemDefault())
                        val lbl = "${date.dayOfMonth}.${date.monthNumber}"
                        val tr = textMeasurer.measure(lbl, TextStyle(fontSize = 8.sp, color = onSurface.copy(alpha = 0.5f)))
                        drawText(tr, topLeft = Offset((x - tr.size.width / 2).coerceIn(0f, size.width - tr.size.width), size.height - tr.size.height))
                    }
                }

                // Trend: a moving average through the time-ordered points; robust for the
                // small samples we get, and only meaningful when points are dated.
                if (timeMode && points.size >= 3) {
                    val window = maxOf(2, points.size / 6)
                    var prev: Offset? = null
                    for (i in points.indices) {
                        val lo = maxOf(0, i - window); val hi = minOf(points.size - 1, i + window)
                        val avg = (lo..hi).sumOf { prices[it] } / (hi - lo + 1)
                        val pt = Offset(px(i), py(avg))
                        prev?.let { drawLine(primary.copy(alpha = 0.35f), it, pt, strokeWidth = 2.5f) }
                        prev = pt
                    }
                }

                // Data points, coloured by condition
                points.forEachIndexed { i, listing ->
                    val x = px(i)
                    val y = py(prices[i])
                    val isNew = listing.condition == Condition.NEW || listing.condition == null
                    val color = if (isNew) primary else tertiary
                    val isTapped = tappedIdx == i
                    val radius = if (isTapped) 7f else 4.5f
                    drawCircle(color.copy(alpha = 0.85f), radius, Offset(x, y))
                    if (isTapped) drawCircle(color, 2.5f, Offset(x, y))
                }
            }

            // Tapped dot: show the full listing card.
            tappedIdx?.let { i ->
                val listing = points[i]
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
    onBlockWord: ((String) -> Unit)? = null,
    searchQuery: String = "",
    modifier: Modifier = Modifier,
) {
    var showBlockDialog by remember { mutableStateOf(false) }
    val dateStr = remember(listing) {
        val instant = listing.soldDate ?: listing.scrapedAt
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val month = local.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        "${local.dayOfMonth} $month ${local.year}"
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
            // Price, most prominent
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
            listing.platformId.country?.let { cc ->
                Text(flagEmoji(cc), style = MaterialTheme.typography.labelSmall)
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
                        Icons.Outlined.DeleteOutline, "Hide this listing",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
            if (onBlockWord != null) {
                IconButton(onClick = { showBlockDialog = true }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Block, "Block a word from this listing",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
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

// === Crawler status row ===

/**
 * Unified platform chips: crawler status and platform filter in one row. Each chip shows the live
 * search state, then acts as a filter when done. Tapping a chip with results filters to that
 * platform; tapping a failed chip shows its error detail below. The "All" chip is always first.
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
            // "All" chip, shown only once there are results.
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

            // One chip per platform, sorted: results by price first, then loading, then errors.
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

        // Error detail, shown when a failed chip is tapped; tap the panel to copy the message.
        val expandedStatus = statuses.find { it.platformId == expandedError }
        if (expandedStatus?.error != null) {
            val clipboardManager = LocalClipboardManager.current
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
                    clipboardManager.setText(AnnotatedString(errorText))
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
                    if (expandedStatus.captchaUrl != null) {
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
