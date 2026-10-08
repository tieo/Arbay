package io.github.tieo.arbay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import io.github.tieo.arbay.chat.sellerMark
import io.github.tieo.arbay.state.OfferNotes
import io.github.tieo.arbay.model.Verdict
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import io.github.tieo.arbay.results.changeSavedSearch
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.DevicePosition
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.rememberCoordDetector
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.design.LookChoice
import io.github.tieo.arbay.design.OfferLayout
import io.github.tieo.arbay.format
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SortMode
import io.github.tieo.arbay.model.tidyTitle
import io.github.tieo.arbay.navigation.Panel
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.results.ResultsState
import io.github.tieo.arbay.results.askingSummary
import io.github.tieo.arbay.results.captchaCount
import io.github.tieo.arbay.results.clearBand
import io.github.tieo.arbay.results.label
import io.github.tieo.arbay.results.listingFoot
import io.github.tieo.arbay.results.listingSpecs
import io.github.tieo.arbay.results.newCount
import io.github.tieo.arbay.results.rememberOpenSearch
import io.github.tieo.arbay.results.rememberResultsState
import io.github.tieo.arbay.results.setBand
import io.github.tieo.arbay.results.setSort
import io.github.tieo.arbay.results.sourceLabel
import io.github.tieo.arbay.results.toggleCondition
import io.github.tieo.arbay.results.toggleNewOnly
import io.github.tieo.arbay.results.toggleSaleType
import io.github.tieo.arbay.results.toggleHiddenSaleType
import io.github.tieo.arbay.results.toggleHiddenCondition
import io.github.tieo.arbay.results.toggleSaved
import io.github.tieo.arbay.results.toggleUnstatedCondition

/**
 * One search's offers, cheapest-first or however the reader orders them, with the price picture and
 * the narrowing above them. An offer opens over the list, a closer look at the search opens as a
 * sheet over it, and back closes either; the list underneath keeps its place.
 */
@Composable
fun ResultsScreen(session: Session, route: Route.Results) {
    val nav = LocalNavigator.current
    val open = rememberOpenSearch(route.source, session.products)
    if (open == null) {
        val products by session.products.products.collectAsState()
        Bare(title = "", onBack = { nav.back() }) {
            Nothing(if (products.isEmpty()) "Loading" else "This search is not here any more.")
        }
        return
    }
    val state = rememberResultsState(session.listings, open)
    Box(Modifier.fillMaxSize()) {
        ResultsList(session, route, state)
        route.listing?.let { id -> OfferScreen(session, id, state) }
    }
    route.panel?.let { panel -> PanelSheet(session, route, panel, state) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ResultsList(session: Session, route: Route.Results, state: ResultsState) {
    val nav = LocalNavigator.current
    val vm = session.listings
    val loading by vm.loading.collectAsState()
    val statuses by vm.platformStatuses.collectAsState()
    val total by vm.totalPlatforms.collectAsState()
    val completed by vm.completedPlatforms.collectAsState()
    val fetched by vm.fetched.collectAsState()
    val notSearched by vm.notSearched.collectAsState()
    val view = state.open.view
    val bookmark = state.open.bookmark
    val shown = state.shown
    val photos = LookChoice.layout == OfferLayout.PHOTOS
    var menu by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    val chat = session.chat
    val selected by chat.selected.collectAsState()
    val conversations by chat.conversations.collectAsState()
    val outbox by chat.outbox.collectAsState()
    val picking = selected.isNotEmpty()
    fun panel(p: Panel) = nav.go(route.copy(panel = p, listing = null))
    fun offer(l: Listing) = if (picking) chat.toggleSelected(l.id) else nav.go(route.copy(listing = l.id, panel = null))
    fun pick(l: Listing) = chat.toggleSelected(l.id)
    fun mark(l: Listing) = sellerMark(conversations.firstOrNull { it.listingId == l.id && it.buying }, outbox.lastOrNull { it.listingId == l.id })
    // Picking belongs to this search; leaving it lets the picks go.
    DisposableEffect(Unit) { onDispose { chat.clearSelection() } }
    if (changing) ChangeSearchDialog(view.name, state.open.saved?.text ?: view.query, onDismiss = { changing = false }) { name, term ->
        changing = false
        state.changeSavedSearch(session.products, name, term)
    }
    if (picking && route.listing == null && route.panel == null) OnBack { chat.clearSelection() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (picking) TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                navigationIcon = { IconButton(onClick = { chat.clearSelection() }) { Icon(Icons.Outlined.Close, "Stop picking") } },
                title = { Text("${selected.size} picked") },
            ) else TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = { IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(view.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        val near = state.open.saved?.let { q -> q.location?.let { "near $it" + (q.radiusKm.takeIf { r -> r > 0 }?.let { r -> " · $r km" } ?: "") } }
                        listOfNotNull(view.query.takeIf { !it.equals(view.name, true) }, near).takeIf { it.isNotEmpty() }?.let {
                            Muted(it.joinToString(" · "), maxLines = 1)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { state.toggleSaved(session.products, vm) }) {
                        Icon(if (bookmark != null) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, if (bookmark != null) "Saved" else "Save")
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (bookmark != null) DropdownMenuItem(text = { Text("Change name or words") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; changing = true })
                        if (bookmark != null) DropdownMenuItem(text = { Text("Watching and alerts") }, leadingIcon = { Icon(Icons.Outlined.Notifications, null) }, onClick = { menu = false; panel(Panel.ALERTS) })
                        if (view.isCar) DropdownMenuItem(text = { Text("Vehicle criteria") }, leadingIcon = { Icon(Icons.Outlined.DirectionsCar, null) }, onClick = { menu = false; panel(Panel.CRITERIA) })
                        DropdownMenuItem(text = { Text("Markets") }, leadingIcon = { Icon(Icons.Outlined.Storefront, null) }, onClick = { menu = false; panel(Panel.MARKETS) })
                        DropdownMenuItem(text = { Text("Words") }, leadingIcon = { Icon(Icons.Outlined.TextFields, null) }, onClick = { menu = false; panel(Panel.WORDS) })
                        DropdownMenuItem(
                            text = { Text(if (photos) "Show as rows" else "Show as photos") },
                            leadingIcon = { Icon(if (photos) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView, null) },
                            onClick = { menu = false; LookChoice.choose(if (photos) OfferLayout.ROWS else OfferLayout.PHOTOS) },
                        )
                        DropdownMenuItem(text = { Text("Ask the markets again") }, leadingIcon = { Icon(Icons.Outlined.Refresh, null) }, onClick = { menu = false; vm.refresh(state.markets) })
                    }
                },
            )
        },
        bottomBar = {
            if (picking) Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().padding(horizontal = arbay.pad, vertical = 10.dp)) {
                Button(onClick = { panel(Panel.WRITE) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selected.size == 1) "Write to the seller" else "Write to ${selected.size} sellers")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                MarketsLine(askingSummary(loading, total, completed, statuses), statuses.captchaCount() > 0, loading, total, completed) { panel(Panel.MARKETS) }
            }
            if (state.summary.count > 0) item { PriceStrip(state) { panel(Panel.PRICES) } }
            item { NarrowingRow(session, state, hiddenCount = state.hidden.sumOf { it.listings.size }, onPanel = ::panel) }
            if (shown.isEmpty()) item {
                Nothing(
                    when {
                        loading -> "Waiting for the first market to answer"
                        notSearched != null -> notSearched!!
                        fetched.isEmpty() -> "No market had one."
                        else -> "Your narrowing hides all ${state.narrowed.allActive.size} of them."
                    },
                )
            }
            if (photos) {
                items(shown.chunked(2), key = { it.first().id }) { pair ->
                    Row(Modifier.padding(horizontal = arbay.pad, vertical = arbay.gap / 2), horizontalArrangement = Arrangement.spacedBy(arbay.gap)) {
                        pair.forEach { l -> OfferTile(l, state, Modifier.weight(1f), l.id in selected, mark(l), onLongClick = { pick(l) }) { offer(l) } }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            } else {
                items(shown, key = { it.id }) { l ->
                    OfferRow(l, state, l.id in selected, mark(l), onLongClick = { pick(l) }) { offer(l) }
                    HorizontalDivider(Modifier.padding(start = arbay.pad), color = arbay.line)
                }
            }
        }
    }
}

/** How the asking is going; a market that wants something from the reader makes it stand out. */
@Composable
private fun MarketsLine(text: String, attention: Boolean, loading: Boolean, total: Int, completed: Int, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = arbay.pad, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Storefront, null, Modifier.size(16.dp), tint = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodySmall, color = if (attention) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) {
            Gap(6.dp)
            if (total > 0) LinearProgressIndicator(progress = { completed.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(2.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

/** Is this a good price: the cheapest, the middle, the sold middle, and the spread drawn small. */
@Composable
private fun PriceStrip(state: ResultsState, onClick: () -> Unit) {
    val s = state.summary
    Row(
        Modifier.padding(horizontal = arbay.pad, vertical = 6.dp).fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, arbay.line, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        s.cheapest?.let { Column { SectionTitle("Cheapest"); PriceText(it, lowest = true) } }
        s.middle?.let { Column { SectionTitle("Middle of ${s.count}"); PriceText(it) } }
        s.sold?.let { Column { SectionTitle("Sold"); PriceText(it.middle) } }
        Spread(s, Modifier.weight(1f), height = 28.dp)
    }
}

/** The narrowing, in one row that scrolls sideways: order, price band, condition, how it is sold,
 *  what is new, and the words and what is not shown. A choice nothing is in is not offered. */
@Composable
private fun NarrowingRow(session: Session, state: ResultsState, hiddenCount: Int, onPanel: (Panel) -> Unit) {
    val vm = session.listings
    val products = session.products
    val sortMode by vm.sortMode.collectAsState()
    val blocked by vm.blockedTerms.collectAsState()
    val narrowed = state.narrowed
    val narrowing = state.narrowing
    var sortMenu by remember { mutableStateOf(false) }
    var bandDialog by remember { mutableStateOf(false) }
    // Nearest-first is someone asking where they are, so that is where the permission is asked.
    val locate = rememberCoordDetector { lat, lon -> DevicePosition.set(lat, lon); vm.setLocation(lat, lon) }

    LazyRow(contentPadding = PaddingValues(horizontal = arbay.pad), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Box {
                Choice(sortMode.label, false, opens = true) { sortMenu = true }
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                            sortMenu = false
                            if (mode == SortMode.NEAREST && DevicePosition.latitude == null) locate()
                            state.setSort(products, vm, mode)
                        })
                    }
                }
            }
        }
        if (narrowed.priceMax > narrowed.priceMin) item {
            val label = if (narrowed.priceFiltered)
                "${narrowed.priceRange.start.toInt()} to ${narrowed.priceRange.endInclusive.toInt()} ${DisplayCurrency.current}" else "Price"
            Choice(label, narrowed.priceFiltered, opens = true) { bandDialog = true }
        }
        Condition.entries.filter { (narrowed.conditionCounts[it] ?: 0) > 0 }.forEach { value ->
            item {
                HidableChoice(
                    "${value.label} ${narrowed.conditionCounts[value]}", value in narrowing.conditions, value in narrowing.hiddenConditions, value.label.lowercase(),
                    onToggle = { state.toggleCondition(products, value) }, onHide = { state.toggleHiddenCondition(products, value) },
                )
            }
        }
        narrowed.conditionCounts[null]?.takeIf { it > 0 && narrowing.conditions.isNotEmpty() }?.let { count ->
            item { Choice("Not stated $count", narrowing.unstatedCondition) { state.toggleUnstatedCondition(products) } }
        }
        if (narrowed.saleTypeCounts.keys.filterNotNull().size > 1 || narrowing.hiddenSaleTypes.isNotEmpty()) {
            SaleType.entries.filter { (narrowed.saleTypeCounts[it] ?: 0) > 0 }.forEach { value ->
                item {
                    HidableChoice(
                        "${value.label} ${narrowed.saleTypeCounts[value]}", value in narrowing.saleTypes, value in narrowing.hiddenSaleTypes,
                        if (value == SaleType.AUCTION) "auctions" else value.label.lowercase(),
                        onToggle = { state.toggleSaleType(products, value) }, onHide = { state.toggleHiddenSaleType(products, value) },
                    )
                }
            }
        }
        if (state.newCount > 0) item { Choice("New ${state.newCount}", narrowing.newOnly) { state.toggleNewOnly() } }
        item { Choice(if (blocked.isNotEmpty()) "Words · ${blocked.size}" else "Words", false) { onPanel(Panel.WORDS) } }
        if (hiddenCount > 0) item { Choice("$hiddenCount not shown", false) { onPanel(Panel.HIDDEN) } }
    }

    if (bandDialog) BandDialog(state, onDismiss = { bandDialog = false }, onApply = { band ->
        bandDialog = false
        if (band == null) state.clearBand(products) else state.setBand(products, band)
    })
}

/** The price band as two amounts; either left empty is open at that end. */
@Composable
private fun BandDialog(state: ResultsState, onDismiss: () -> Unit, onApply: (ClosedFloatingPointRange<Float>?) -> Unit) {
    val n = state.narrowed
    var low by remember { mutableStateOf(if (n.priceRange.start > n.priceMin) n.priceRange.start.toInt().toString() else "") }
    var high by remember { mutableStateOf(if (n.priceRange.endInclusive < n.priceMax) n.priceRange.endInclusive.toInt().toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Price, in ${DisplayCurrency.current}") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(low, { low = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("From") }, placeholder = { Text("${n.priceMin.toInt()}") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(high, { high = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Up to") }, placeholder = { Text("${n.priceMax.toInt()}") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = low.toFloatOrNull()
                val b = high.toFloatOrNull()
                onApply(if (a == null && b == null) null else (a ?: n.priceMin)..(b ?: n.priceMax))
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = { onApply(null) }) { Text("Any price") } },
    )
}

/** One offer as a row: its photo, what it is, where it is from, and its price. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OfferRow(listing: Listing, state: ResultsState, picked: Boolean, mark: String?, onLongClick: () -> Unit, onClick: () -> Unit) {
    val copies = state.elsewhere[listing.id].orEmpty()
    val lowest = listing.id in state.summary.cheapestIds
    val fresh = listing.id in state.open.newListingIds
    Row(
        Modifier.fillMaxWidth()
            .background(if (picked) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = arbay.pad, vertical = arbay.gap),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box {
            Photo(listing.imageUrls.firstOrNull { it.isNotBlank() }, Modifier.size(68.dp).clip(MaterialTheme.shapes.small), listing.title)
            if (picked) PickedMark(Modifier.align(Alignment.TopStart).padding(4.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(listing.title.tidyTitle(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Muted((listOf(sourceLabel(listing, copies)) + listingSpecs(listing).map { it.toString() }).joinToString(" · "), maxLines = 1)
            listingFoot(listing).takeIf { it.isNotEmpty() }?.let { Muted(it.joinToString(" · "), maxLines = 1) }
            mark?.let { SellerMark(it) }
            NoteLine(listing.id)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PriceText(listing.comparablePrice, lowest = lowest)
            if (listing.saleType == SaleType.AUCTION) Muted(listing.bidCount?.let { "$it bids" } ?: "auction")
            if (fresh) Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** One offer as a photo with its price beneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OfferTile(listing: Listing, state: ResultsState, modifier: Modifier, picked: Boolean, mark: String?, onLongClick: () -> Unit, onClick: () -> Unit) {
    val copies = state.elsewhere[listing.id].orEmpty()
    Column(
        modifier.clip(MaterialTheme.shapes.medium).background(if (picked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(if (picked) 2.dp else 1.dp, if (picked) MaterialTheme.colorScheme.primary else arbay.line, MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box {
            Photo(listing.imageUrls.firstOrNull { it.isNotBlank() }, Modifier.fillMaxWidth().aspectRatio(1f), listing.title)
            if (picked) PickedMark(Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PriceText(listing.comparablePrice, lowest = listing.id in state.summary.cheapestIds)
            Text(listing.title.tidyTitle(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Muted(sourceLabel(listing, copies), maxLines = 1)
            mark?.let { SellerMark(it) }
        }
    }
}

/** A saved search's words and name, changed together; new words ask the markets again. */
@Composable
private fun ChangeSearchDialog(name: String, term: String, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var newTerm by remember { mutableStateOf(term) }
    var newName by remember { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change the search") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newTerm, { newTerm = it }, Modifier.fillMaxWidth(), label = { Text("Searched for") }, singleLine = true)
                OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = newTerm.isNotBlank(), onClick = { onSave(newName, newTerm) }) { Text("Search again") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A tick over a picked offer's photo. */
@Composable
private fun PickedMark(modifier: Modifier) {
    Icon(
        Icons.Outlined.Check, "Picked",
        modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primary).padding(3.dp),
        tint = MaterialTheme.colorScheme.onPrimary,
    )
}

/** The verdict on this offer, when one was written; "Avoid" alone in the warning colour. */
@Composable
fun NoteLine(listingId: String, full: Boolean = false) {
    val notes by OfferNotes.notes.collectAsState()
    val note = notes[listingId] ?: return
    val avoid = note.verdict == Verdict.AVOID
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(note.verdict.label) }
            append(" · " + note.text)
            if (full) append("  (" + note.by + ")")
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (avoid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        maxLines = if (full) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
    )
}

/** Where the user stands with this offer's seller. */
@Composable
private fun SellerMark(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A screen with only a back arrow and a title, for the moments a search is not there to show. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bare(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding -> Box(Modifier.padding(padding)) { content() } }
}
