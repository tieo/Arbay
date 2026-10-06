package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.DevicePosition
import io.github.tieo.arbay.DisplayCurrency
import io.github.tieo.arbay.ImportRules
import io.github.tieo.arbay.format
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.history.SearchHistoryStore
import io.github.tieo.arbay.history.summary
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SearchReach
import io.github.tieo.arbay.model.SortMode
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.model.tidyTitle
import io.github.tieo.arbay.results.Narrowing
import io.github.tieo.arbay.results.ResultsView
import io.github.tieo.arbay.results.hiddenListings
import io.github.tieo.arbay.results.label
import io.github.tieo.arbay.results.listingFoot
import io.github.tieo.arbay.results.listingSpecs
import io.github.tieo.arbay.results.marketsToAsk
import io.github.tieo.arbay.results.narrow
import io.github.tieo.arbay.results.sourceLabel
import io.github.tieo.arbay.results.withBand
import io.github.tieo.arbay.model.PlatformSearchStatus
import kotlinx.browser.document
import kotlinx.browser.window
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Section
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent

/**
 * One open search: what it is, where its filters are kept, and what it found before it was opened.
 * A saved search keeps its filters on the bookmark; any other search keeps them in history.
 */
private class OpenSearch(
    val view: ResultsView,
    val bookmark: TrackedProduct?,
    val saved: SearchQuery?,
    val newListingIds: Set<String>,
)

/** The middle and right panes for a search: its results, and the listing chosen from them. */
@Composable
fun ResultsAndDetail(app: WebApp, route: Route) {
    val products by app.products.products.collectAsState()
    val status by app.products.status.collectAsState()
    val history by SearchHistoryStore.entries.collectAsState()

    // What a saved search found since it was last opened, read once as it opens: opening it is
    // what clears it.
    val openedKey = when (route) {
        is Route.Saved -> "saved:${route.id}"
        is Route.Search -> "search:${route.text}"
        else -> ""
    }
    var backlog by remember(openedKey) { mutableStateOf<Set<String>?>(null) }

    val open: OpenSearch? = when (route) {
        is Route.Saved -> products.firstOrNull { it.id == route.id }?.let { product ->
            OpenSearch(ResultsView.of(product), product, product.searchQuery, backlog.orEmpty())
        }
        is Route.Search -> {
            val bookmark = products.firstOrNull {
                it.searchQuery.text.trim().equals(route.text.trim(), ignoreCase = true)
            }
            val entry = history.firstOrNull {
                it.searchQuery.text.trim().equals(route.text.trim(), ignoreCase = true) &&
                    it.searchQuery.category == MarketGroup.GENERAL
            }
            OpenSearch(
                ResultsView.of(route.text, route.text, platforms = null, category = MarketGroup.GENERAL),
                bookmark, bookmark?.searchQuery ?: entry?.searchQuery, emptySet(),
            )
        }
        else -> null
    }

    LaunchedEffect(openedKey, status.isNotEmpty()) {
        if (route is Route.Saved && backlog == null && status.isNotEmpty()) {
            backlog = status[route.id]?.newListingIds.orEmpty().toSet()
            app.products.markOpened(route.id)
        }
        if (route is Route.Search) {
            SearchHistoryStore.recordOpen(route.text, route.text, null, null, MarketGroup.GENERAL)
        }
    }

    Main({ classes("results") }) {
        if (open == null) {
            Div({ classes("empty") }) { Text(if (products.isEmpty()) "Loading saved searches…" else "This saved search is gone.") }
        } else {
            ResultsList(app, route, open)
        }
    }
    Section({ classes("detail") }) {
        DetailPane(app, route)
    }
}

@Composable
private fun ResultsList(app: WebApp, route: Route, open: OpenSearch) {
    val vm = app.listings
    val view = open.view
    val listings by vm.listings.collectAsState()
    val elsewhere by vm.elsewhere.collectAsState()
    val fetched by vm.fetched.collectAsState()
    val marketBasis by vm.marketBasis.collectAsState()
    val dropped by vm.droppedBySearch.collectAsState()
    val banned by vm.bannedIds.collectAsState()
    val blocked by vm.blockedTerms.collectAsState()
    val loading by vm.loading.collectAsState()
    val statuses by vm.platformStatuses.collectAsState()
    val total by vm.totalPlatforms.collectAsState()
    val completed by vm.completedPlatforms.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val shownMarkets by vm.shownMarkets.collectAsState()

    // The search's own settings go into the view model as it opens, the way the phone's results
    // screen hands them over.
    val savedBlocked = open.saved?.excludeKeywords.orEmpty()
    LaunchedEffect(view.query, savedBlocked) { vm.setBlockedTerms(savedBlocked) }
    LaunchedEffect(view.query, open.saved != null) {
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

    val isCar = view.isCar
    val markets = remember(view.platforms, isCar, shownMarkets) { marketsToAsk(view.platforms, isCar, shownMarkets) }
    val crawlKey = listOf(view.query, view.filters, view.aliases, markets.map { it.name }, open.saved?.location, open.saved?.radiusKm).toString()
    LaunchedEffect(crawlKey) {
        vm.search(
            view.query, markets, view.filters, excludeKeywords = savedBlocked, aliases = view.aliases,
            reach = open.saved?.reach ?: SearchReach(),
            near = open.saved?.location,
            radiusKm = open.saved?.radiusKm?.takeIf { it > 0 },
        )
    }

    // The reader's narrowing, read out of the saved search once per search opened and theirs from
    // then on, so writing a choice back never re-seeds the others.
    val openedSearch = "${view.name}|${view.query}"
    var narrowing by remember(openedSearch) { mutableStateOf<Narrowing?>(null) }
    if (narrowing == null && (open.saved != null || route is Route.Search)) narrowing = Narrowing.of(open.saved)
    val current = narrowing ?: Narrowing()
    val money = Triple(DisplayCurrency.current, DisplayCurrency.rates, ImportRules.current)
    val narrowed = remember(listings, current, money, open.newListingIds) { narrow(listings, current, open.newListingIds) }
    val hidden = remember(narrowed, fetched, marketBasis, banned, blocked, dropped) {
        hiddenListings(narrowed, current, fetched, marketBasis, banned, blocked, dropped, open.newListingIds)
    }

    fun persist(edit: (SearchQuery) -> SearchQuery) {
        val base = open.saved ?: SearchQuery(text = view.query, category = view.category)
        val next = edit(base)
        val bookmark = open.bookmark
        if (bookmark != null) app.products.updateProduct(bookmark.copy(searchQuery = next))
        else SearchHistoryStore.record(view.name, next)
    }

    val selected = route.listing
    val shown = narrowed.displayed
    // Up and down (or j and k) walk the list the way they do in a mail client; the listing shown on
    // the right follows.
    DisposableEffect(shown.map { it.id }, selected) {
        val handler: (org.w3c.dom.events.Event) -> Unit = handler@{ event ->
            val key = event as KeyboardEvent
            val target = document.activeElement
            if (target is HTMLInputElement || target is HTMLSelectElement || target is HTMLTextAreaElement) return@handler
            val step = when (key.key) {
                "ArrowDown", "j" -> 1
                "ArrowUp", "k" -> -1
                else -> return@handler
            }
            if (shown.isEmpty()) return@handler
            key.preventDefault()
            val at = shown.indexOfFirst { it.id == selected }
            val next = shown[(if (at < 0) 0 else at + step).coerceIn(0, shown.lastIndex)]
            Router.replace(route.withListing(next.id))
            // Focus moves with it, so the ring a click left on another row does not mark a second one.
            val row = document.getElementById("row-${next.id}")?.querySelector("a")
            row?.asDynamic()?.focus(js("({ preventScroll: true })"))
            row?.asDynamic()?.scrollIntoView(js("({ block: 'nearest' })"))
        }
        document.addEventListener("keydown", handler)
        onDispose { document.removeEventListener("keydown", handler) }
    }

    Header({ classes("results-head") }) {
        Div({ classes("title-row") }) {
            H1 { Text(view.name) }
            val bookmark = open.bookmark
            Button(attrs = {
                classes("ghost")
                attr("aria-pressed", (bookmark != null).toString())
                onClick {
                    if (bookmark != null) app.products.deleteProduct(bookmark.id)
                    else app.products.createProduct(view.name, open.saved ?: SearchQuery(
                        text = view.query, category = view.category, platforms = markets,
                    ))
                }
            }) { Text(if (bookmark != null) "Saved" else "Save search") }
        }
        P({ classes("status") }) {
            Text(
                when {
                    loading && total > 0 -> "Asking the markets: $completed of $total have answered · ${shown.size} so far"
                    loading -> "Asking the markets…"
                    else -> "${shown.size} offers across ${shown.map { it.platformId }.distinct().size} markets"
                },
            )
            val failed = statuses.count { it.status !in setOf(PlatformSearchStatus.DONE, PlatformSearchStatus.SEARCHING, PlatformSearchStatus.PENDING) }
            if (!loading && failed > 0) Span({ classes("muted") }) { Text(" · $failed could not be asked") }
        }
        FilterBar(narrowed, current, sortMode,
            onNarrow = { next -> narrowing = next },
            onSort = { mode -> vm.setSortMode(mode); persist { it.copy(sort = mode) } },
            onBand = { band -> narrowing = current.copy(band = band); persist { it.withBand(band, narrowed) } },
            onConditions = { set, unstated ->
                narrowing = current.copy(conditions = set, unstatedCondition = unstated)
                persist { it.copy(condition = set.toList().takeIf { l -> l.isNotEmpty() }, conditionUnstated = unstated) }
            },
            onSaleTypes = { set, unstated ->
                narrowing = current.copy(saleTypes = set, unstatedSaleType = unstated)
                persist { it.copy(saleTypes = set.toList().takeIf { l -> l.isNotEmpty() }, saleTypeUnstated = unstated) }
            },
            newCount = narrowed.allActive.count { it.id in open.newListingIds },
        )
        val hiddenCount = hidden.sumOf { it.listings.size }
        if (hiddenCount > 0) {
            P({ classes("hidden-line") }) {
                Text("Not shown: $hiddenCount · " + hidden.maxBy { it.listings.size }.label)
            }
        }
    }

    Div({ classes("rows") }) {
        if (shown.isEmpty() && !loading) {
            Div({ classes("empty") }) {
                Text(if (fetched.isEmpty()) "No market had one." else "Your filters hide all ${narrowed.allActive.size} of them.")
            }
        }
        shown.forEach { listing ->
            val copies = elsewhere[listing.id].orEmpty()
            ListingRow(
                listing, copies, active = listing.id == selected,
                to = route.withListing(listing.id),
                onHide = { (listOf(listing) + copies).forEach(vm::ban) },
            )
        }
    }
}

@Composable
private fun ListingRow(listing: Listing, copies: List<Listing>, active: Boolean, to: Route, onHide: () -> Unit) {
    Div({
        id("row-${listing.id}")
        classes(*listOfNotNull("row", "active".takeIf { active }).toTypedArray())
    }) {
        RouteLink(to, classes = listOf("row-link"), replace = true) {
            val image = listing.imageUrls.firstOrNull { it.isNotBlank() }
            if (image != null) {
                Img(src = image, alt = "") {
                    classes("thumb")
                    attr("loading", "lazy")
                    attr("referrerpolicy", "no-referrer")
                }
            } else {
                Div({ classes("thumb") }) {}
            }
            Div({ classes("row-text") }) {
                Span({ classes("row-title") }) { Text(listing.title.tidyTitle()) }
                Span({ classes("row-meta") }) {
                    Text((listOf(sourceLabel(listing, copies)) + listingSpecs(listing).map { it.toString() }).joinToString(" · "))
                }
                listingFoot(listing).takeIf { it.isNotEmpty() }?.let { foot ->
                    Span({ classes("row-foot") }) { Text(foot.joinToString(" · ")) }
                }
            }
            Div({ classes("row-price") }) {
                Span({ classes("price") }) { Text(listing.comparablePrice.format()) }
                listing.oldPrice?.let { Span({ classes("old-price") }) { Text(it.format()) } }
            }
        }
        Button(attrs = {
            classes("icon", "hide")
            attr("title", "Hide this listing")
            attr("aria-label", "Hide this listing")
            onClick { onHide() }
        }) { Text("✕") }
    }
}

@Composable
private fun FilterBar(
    narrowed: io.github.tieo.arbay.results.Narrowed,
    narrowing: Narrowing,
    sortMode: SortMode,
    onNarrow: (Narrowing) -> Unit,
    onSort: (SortMode) -> Unit,
    onBand: (ClosedFloatingPointRange<Float>) -> Unit,
    onConditions: (Set<Condition>, Boolean) -> Unit,
    onSaleTypes: (Set<SaleType>, Boolean) -> Unit,
    newCount: Int,
) {
    Div({ classes("filters") }) {
        Select({
            classes("control")
            attr("aria-label", "Order")
            onChange { event -> event.value?.let { v -> onSort(SortMode.valueOf(v)) } }
        }) {
            SortMode.entries.forEach { mode ->
                Option(mode.name, { if (mode == sortMode) selected() }) { Text(mode.label) }
            }
        }

        // The band in whole display units; an empty end is no bound.
        Label(attrs = { classes("band") }) {
            Text(DisplayCurrency.current)
            BandInput(narrowed.priceRange.start, narrowed.priceMin, "from") { low ->
                onBand((low ?: narrowed.priceMin)..narrowed.priceRange.endInclusive)
            }
            Text("–")
            BandInput(narrowed.priceRange.endInclusive, narrowed.priceMax, "to") { high ->
                onBand(narrowed.priceRange.start..(high ?: narrowed.priceMax))
            }
        }

        // One chip per condition the results hold, each on or off by itself; none ticked is every
        // condition. "Not stated" is its own answer, for listings whose market never said.
        Condition.entries.filter { (narrowed.conditionCounts[it] ?: 0) > 0 }.forEach { value ->
            Chip("${value.label} (${narrowed.conditionCounts[value]})", value in narrowing.conditions) {
                val next = if (value in narrowing.conditions) narrowing.conditions - value else narrowing.conditions + value
                onConditions(next, narrowing.unstatedCondition)
            }
        }
        narrowed.conditionCounts[null]?.takeIf { it > 0 && narrowed.conditionCounts.size > 1 }?.let { count ->
            Chip("Not stated ($count)", narrowing.unstatedCondition) {
                onConditions(narrowing.conditions, !narrowing.unstatedCondition)
            }
        }
        // Offered only where the results hold both kinds: a screen of shop listings has no auction
        // to take out.
        if (narrowed.saleTypeCounts.keys.filterNotNull().size > 1) {
            SaleType.entries.filter { (narrowed.saleTypeCounts[it] ?: 0) > 0 }.forEach { value ->
                Chip("${value.label} (${narrowed.saleTypeCounts[value]})", value in narrowing.saleTypes) {
                    val next = if (value in narrowing.saleTypes) narrowing.saleTypes - value else narrowing.saleTypes + value
                    onSaleTypes(next, narrowing.unstatedSaleType)
                }
            }
            narrowed.saleTypeCounts[null]?.takeIf { it > 0 }?.let { count ->
                Chip("Not stated ($count)", narrowing.unstatedSaleType) {
                    onSaleTypes(narrowing.saleTypes, !narrowing.unstatedSaleType)
                }
            }
        }
        if (newCount > 0) {
            Chip("New ($newCount)", narrowing.newOnly) { onNarrow(narrowing.copy(newOnly = !narrowing.newOnly)) }
        }
    }
}

@Composable
private fun BandInput(value: Float, trackEnd: Float, label: String, onCommit: (Float?) -> Unit) {
    Input(InputType.Number) {
        classes("control", "band-input")
        attr("aria-label", "Price $label")
        attr("placeholder", trackEnd.toInt().toString())
        value(if (value == trackEnd) "" else value.toInt().toString())
        onChange { event -> onCommit(event.value?.toFloat()) }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onToggle: () -> Unit) {
    Button(attrs = {
        classes(*listOfNotNull("chip", "on".takeIf { on }).toTypedArray())
        attr("aria-pressed", on.toString())
        onClick { onToggle() }
    }) { Text(text) }
}

/** Before a search is open: the searches run lately, newest first, each a link back to it. */
@Composable
fun Welcome(app: WebApp) {
    val history by SearchHistoryStore.entries.collectAsState()
    Main({ classes("results") }) {
        val recent = history.filter { it.searchQuery.category == MarketGroup.GENERAL }
        Header({ classes("results-head") }) { H1 { Text("Recent searches") } }
        Div({ classes("rows") }) {
            if (recent.isEmpty()) Div({ classes("empty") }) { Text("None yet in this browser.") }
            recent.forEach { entry ->
                RouteLink(Route.Search(entry.searchQuery.text), classes = listOf("recent")) {
                    Span({ classes("row-title") }) { Text(entry.name) }
                    Span({ classes("row-meta") }) { Text(entry.summary()) }
                }
            }
        }
    }
    Section({ classes("detail") }) {}
}

@Composable
fun NotYet(name: String) {
    Main({ classes("results") }) {
        Div({ classes("empty") }) { Text(name) }
    }
    Section({ classes("detail") }) {}
}
