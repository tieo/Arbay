package io.github.tieo.arbay.web

import io.github.tieo.arbay.results.OpenSearch
import io.github.tieo.arbay.results.ResultsState
import io.github.tieo.arbay.results.rememberOpenSearch
import io.github.tieo.arbay.results.rememberResultsState
import io.github.tieo.arbay.results.askingSummary
import io.github.tieo.arbay.results.captchaCount
import io.github.tieo.arbay.results.newCount
import io.github.tieo.arbay.results.setBand
import io.github.tieo.arbay.results.setSort
import io.github.tieo.arbay.results.toggleCondition
import io.github.tieo.arbay.results.toggleNewOnly
import io.github.tieo.arbay.results.toggleSaleType
import io.github.tieo.arbay.results.toggleHiddenSaleType
import io.github.tieo.arbay.results.toggleHiddenCondition
import io.github.tieo.arbay.results.toggleSaved
import io.github.tieo.arbay.results.toggleUnstatedCondition
import io.github.tieo.arbay.navigation.Panel
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.navigation.Source
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
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.design.LookChoice
import io.github.tieo.arbay.design.OfferLayout
import io.github.tieo.arbay.history.SearchHistoryStore
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.PlatformSearchStatus
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SearchReach
import io.github.tieo.arbay.model.SortMode
import io.github.tieo.arbay.model.TrackedProduct
import io.github.tieo.arbay.model.tidyTitle
import io.github.tieo.arbay.results.Hidden
import io.github.tieo.arbay.results.Narrowed
import io.github.tieo.arbay.results.Narrowing
import io.github.tieo.arbay.results.PriceSummary
import io.github.tieo.arbay.results.ResultsView
import io.github.tieo.arbay.results.bookmarkQuery
import io.github.tieo.arbay.results.hiddenListings
import io.github.tieo.arbay.results.label
import io.github.tieo.arbay.results.listingFoot
import io.github.tieo.arbay.results.listingSpecs
import io.github.tieo.arbay.results.marketsToAsk
import io.github.tieo.arbay.results.narrow
import io.github.tieo.arbay.results.priceSummary
import io.github.tieo.arbay.results.sourceLabel
import io.github.tieo.arbay.results.changeSavedSearch
import io.github.tieo.arbay.state.OfferNotes
import io.github.tieo.arbay.model.Verdict
import org.jetbrains.compose.web.dom.B
import org.jetbrains.compose.web.dom.Form
import io.github.tieo.arbay.results.withBand
import io.github.tieo.arbay.viewmodel.PlatformStatus
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.dom.Aside
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
import org.jetbrains.compose.web.dom.Select
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent

/** The middle and right panes of a search. */
@Composable
fun ResultsScreen(app: WebApp, route: Route.Results) {
    val products by app.products.products.collectAsState()
    val source = route.source
    val open = rememberOpenSearch(source, app.products)

    if (open == null) {
        Main({ classes("results") }) {
            Div({ classes("empty") }) {
                if (products.isEmpty() && source is Source.Saved) Busy() else Text("This search is not here any more.")
            }
        }
        Aside({ classes("inspector") }) {}
        return
    }
    ResultsPanes(app, route, open)
}

@Composable
private fun ResultsPanes(app: WebApp, route: Route.Results, open: OpenSearch) {
    val vm = app.listings
    val view = open.view
    val listings by vm.listings.collectAsState()
    val elsewhere by vm.elsewhere.collectAsState()
    val fetched by vm.fetched.collectAsState()
    val loading by vm.loading.collectAsState()
    val statuses by vm.platformStatuses.collectAsState()
    val total by vm.totalPlatforms.collectAsState()
    val completed by vm.completedPlatforms.collectAsState()
    val sortMode by vm.sortMode.collectAsState()
    val shownMarkets by vm.shownMarkets.collectAsState()
    val priceHistory by vm.priceHistory.collectAsState()
    val banned by vm.bannedIds.collectAsState()
    val blocked by vm.blockedTerms.collectAsState()
    val marketBasis by vm.marketBasis.collectAsState()
    val dropped by vm.droppedBySearch.collectAsState()
    val notSearched by vm.notSearched.collectAsState()

    val state = rememberResultsState(vm, open)
    val narrowed = state.narrowed
    val summary = state.summary
    val hidden = state.hidden
    val markets = state.markets
    val shown = narrowed.displayed
    val selected = route.listing
    KeyboardWalk(route, shown.map { it.id })
    var changing by remember(open.bookmark?.id) { mutableStateOf(false) }
    val picking = app.chat.selected.collectAsState().value.isNotEmpty()

    Main({ classes("results") }) {
        Header({ classes("results-head") }) {
            Div({ classes("title-row") }) {
                Div({ classes("title-text") }) {
                    H1 { Text(view.name) }
                    val where = listOfNotNull(
                        view.query.takeIf { !it.equals(view.name, ignoreCase = true) },
                        open.saved?.let { q -> q.location?.let { place -> "near $place" + (q.radiusKm.takeIf { it > 0 }?.let { " · $it km" } ?: "") } },
                    )
                    if (where.isNotEmpty()) P({ classes("subtitle") }) { Text(where.joinToString(" · ")) }
                }
                if (open.bookmark != null) IconButton(Glyph.Pencil, "Change the name or the words", pressed = changing) { changing = !changing }
                if (view.isCar) {
                    IconButton(Glyph.Car, "Vehicle criteria", pressed = route.panel == Panel.CRITERIA) { togglePanel(route, Panel.CRITERIA) }
                }
                val bookmark = open.bookmark
                if (bookmark != null) {
                    IconButton(Glyph.Bell, "Watching and alerts", pressed = route.panel == Panel.ALERTS) { togglePanel(route, Panel.ALERTS) }
                }
                IconButton(Glyph.Refresh, "Ask the markets again") { vm.refresh(markets) }
                QuietButton(if (bookmark != null) "Saved" else "Save", Glyph.Bookmark, pressed = bookmark != null) {
                    state.toggleSaved(app.products, vm)
                }
            }

            if (changing) ChangeSearch(open, view.name) { name, term -> state.changeSavedSearch(app.products, name, term); changing = false }
            MarketsLine(route, loading, total, completed, statuses)
            PriceStrip(route, summary)
            Toolbar(app, route, state, sortMode, blocked.size, hidden.sumOf { it.listings.size })
            PickBar(app, route)
        }

        val photos = LookChoice.layout == OfferLayout.PHOTOS
        Div({ classes(if (photos) "tiles" else "rows") }) {
            if (shown.isEmpty()) {
                Div({ classes("empty") }) {
                    Text(
                        when {
                            loading -> "Waiting for the first market to answer…"
                            notSearched != null -> notSearched!!
                            fetched.isEmpty() -> "No market had one."
                            else -> "Your narrowing hides all ${narrowed.allActive.size} of them."
                        },
                    )
                }
            }
            shown.forEach { listing ->
                val copies = elsewhere[listing.id].orEmpty()
                val to = route.copy(listing = listing.id, panel = null)
                val lowest = listing.id in summary.cheapestIds
                val hide = { (listOf(listing) + copies).forEach(vm::ban) }
                if (photos) OfferTile(app, listing, copies, listing.id == selected, lowest, to, picking, hide)
                else OfferRow(app, listing, copies, listing.id == selected, lowest, to, picking, hide)
            }
        }
    }

    Aside({ classes("inspector") }) {
        val panel = route.panel
        when {
            panel != null -> PanelView(app, route, panel, state)
            selected != null -> DetailPane(app, route, selected, state)
            else -> PricesPanel(app, route, state)
        }
    }
}

/** Opens a side panel, or closes it when it is the one open. */
fun togglePanel(route: Route.Results, panel: Panel) {
    Router.replace(if (route.panel == panel) route.copy(panel = null) else route.copy(panel = panel, listing = null))
}

/** Up and down (or j and k) walk the offers the way they walk a mail client's inbox; Escape closes
 *  whatever the right pane shows. */
@Composable
private fun KeyboardWalk(route: Route.Results, ids: List<String>) {
    DisposableEffect(ids, route) {
        val handler: (org.w3c.dom.events.Event) -> Unit = handler@{ event ->
            val key = event as KeyboardEvent
            val target = document.activeElement
            if (target is HTMLInputElement || target is HTMLSelectElement || target is HTMLTextAreaElement) return@handler
            if (key.key == "Escape" && (route.listing != null || route.panel != null)) {
                Router.replace(route.copy(listing = null, panel = null))
                return@handler
            }
            val step = when (key.key) { "ArrowDown", "j" -> 1; "ArrowUp", "k" -> -1; else -> return@handler }
            if (ids.isEmpty()) return@handler
            key.preventDefault()
            val at = ids.indexOf(route.listing)
            val next = ids[(if (at < 0) 0 else at + step).coerceIn(0, ids.lastIndex)]
            Router.replace(route.copy(listing = next, panel = null))
            val card = document.getElementById("offer-$next")
            card?.querySelector("a")?.asDynamic()?.focus(js("({ preventScroll: true })"))
            card?.asDynamic()?.scrollIntoView(js("({ block: 'nearest' })"))
        }
        document.addEventListener("keydown", handler)
        onDispose { document.removeEventListener("keydown", handler) }
    }
}

/** How the asking is going, in one line; a market that wants something from the reader says so. */
@Composable
private fun MarketsLine(route: Route.Results, loading: Boolean, total: Int, completed: Int, statuses: List<PlatformStatus>) {
    val captcha = statuses.captchaCount()
    Button(attrs = {
        classes(*listOfNotNull("markets-line", "attention".takeIf { captcha > 0 }, "on".takeIf { route.panel == Panel.MARKETS }).toTypedArray())
        onClick { togglePanel(route, Panel.MARKETS) }
    }) {
        Icon(Glyph.Store, 16)
        Span {
            Text(askingSummary(loading, total, completed, statuses))
        }
        if (loading && total > 0) {
            Span({ classes("progress") }) {
                Span({ classes("progress-fill"); style { property("width", "${completed * 100 / total}%") } }) {}
            }
        }
    }
}

/** Is this a good price: the cheapest, the middle, the sold middle, and the spread drawn small. */
@Composable
private fun PriceStrip(route: Route.Results, summary: PriceSummary) {
    if (summary.count == 0) return
    Button(attrs = {
        classes(*listOfNotNull("price-strip", "on".takeIf { route.panel == Panel.PRICES }).toTypedArray())
        attr("aria-label", "The price picture")
        onClick { togglePanel(route, Panel.PRICES) }
    }) {
        Div({ classes("figure") }) {
            Span({ classes("figure-label") }) { Text("Cheapest") }
            summary.cheapest?.let { Price(it, lowest = true) }
        }
        Div({ classes("figure") }) {
            Span({ classes("figure-label") }) { Text("Middle of ${summary.count}") }
            summary.middle?.let { Price(it) }
        }
        summary.sold?.let { sold ->
            Div({ classes("figure") }) {
                Span({ classes("figure-label") }) { Text("Sold, middle of ${sold.count}") }
                Price(sold.middle)
            }
        }
        Spread(summary)
    }
}

/** The spread of prices as small bars, cheapest at the left; the cheapest bar stands apart. */
@Composable
fun Spread(summary: PriceSummary, tall: Boolean = false) {
    val peak = (summary.spread.maxOrNull() ?: 0).coerceAtLeast(1)
    Div({ classes(*listOfNotNull("spread", "tall".takeIf { tall }).toTypedArray()); attr("aria-hidden", "true") }) {
        summary.spread.forEachIndexed { index, count ->
            Span({
                classes(*listOfNotNull("bar", "lowest".takeIf { index == 0 && count > 0 }).toTypedArray())
                style { property("height", "${if (count == 0) 2 else 8 + count * 92 / peak}%") }
            }) {}
        }
    }
}

@Composable
private fun Toolbar(app: WebApp, route: Route.Results, state: ResultsState, sortMode: SortMode, blockedCount: Int, hiddenCount: Int) {
    val narrowed = state.narrowed
    val narrowing = state.narrowing
    Div({ classes("toolbar") }) {
        Select({
            classes("control")
            attr("aria-label", "Order")
            onChange { event ->
                event.value?.let { v ->
                    val mode = SortMode.valueOf(v)
                    if (mode == SortMode.NEAREST && DevicePosition.latitude == null) {
                        readPosition { lat, lon -> DevicePosition.set(lat, lon); app.listings.setLocation(lat, lon) }
                    }
                    state.setSort(app.products, app.listings, mode)
                }
            }
        }) {
            SortMode.entries.forEach { mode -> Option(mode.name, { if (mode == sortMode) selected() }) { Text(mode.label) } }
        }

        Label(attrs = { classes("band") }) {
            BandInput(narrowed.priceRange.start, narrowed.priceMin, "Lowest price") { low ->
                state.setBand(app.products, (low ?: narrowed.priceMin)..narrowed.priceRange.endInclusive)
            }
            Span({ classes("muted") }) { Text("–") }
            BandInput(narrowed.priceRange.endInclusive, narrowed.priceMax, "Highest price") { high ->
                state.setBand(app.products, narrowed.priceRange.start..(high ?: narrowed.priceMax))
            }
            Span({ classes("muted") }) { Text(DisplayCurrency.current) }
        }

        Condition.entries.filter { (narrowed.conditionCounts[it] ?: 0) > 0 }.forEach { value ->
            HidableChip(
                "${value.label} ${narrowed.conditionCounts[value]}", value in narrowing.conditions, value in narrowing.hiddenConditions, value.label.lowercase(),
                onToggle = { state.toggleCondition(app.products, value) }, onHide = { state.toggleHiddenCondition(app.products, value) },
            )
        }
        // "Not stated" only matters once a condition is picked: until then every listing shows.
        narrowed.conditionCounts[null]?.takeIf { it > 0 && narrowing.conditions.isNotEmpty() }?.let { count ->
            Chip("Not stated $count", narrowing.unstatedCondition) { state.toggleUnstatedCondition(app.products) }
        }
        if (narrowed.saleTypeCounts.keys.filterNotNull().size > 1 || narrowing.hiddenSaleTypes.isNotEmpty()) {
            SaleType.entries.filter { (narrowed.saleTypeCounts[it] ?: 0) > 0 }.forEach { value ->
                HidableChip(
                    "${value.label} ${narrowed.saleTypeCounts[value]}", value in narrowing.saleTypes, value in narrowing.hiddenSaleTypes,
                    if (value == SaleType.AUCTION) "auctions" else value.label.lowercase(),
                    onToggle = { state.toggleSaleType(app.products, value) }, onHide = { state.toggleHiddenSaleType(app.products, value) },
                )
            }
        }
        if (state.newCount > 0) Chip("New ${state.newCount}", narrowing.newOnly) { state.toggleNewOnly() }

        Span({ classes("toolbar-gap") }) {}
        QuietButton(if (blockedCount > 0) "Words · $blockedCount" else "Words", Glyph.Words, pressed = route.panel == Panel.WORDS) { togglePanel(route, Panel.WORDS) }
        if (hiddenCount > 0) QuietButton("$hiddenCount not shown", Glyph.EyeOff, pressed = route.panel == Panel.HIDDEN) { togglePanel(route, Panel.HIDDEN) }
        Div({ classes("segmented") }) {
            IconButton(Glyph.Rows, "Rows", pressed = LookChoice.layout == OfferLayout.ROWS) { LookChoice.choose(OfferLayout.ROWS) }
            IconButton(Glyph.Photos, "Photos", pressed = LookChoice.layout == OfferLayout.PHOTOS) { LookChoice.choose(OfferLayout.PHOTOS) }
        }
    }
}

@Composable
private fun BandInput(value: Float, trackEnd: Float, label: String, onCommit: (Float?) -> Unit) {
    Input(InputType.Number) {
        classes("control", "band-input")
        attr("aria-label", label)
        attr("placeholder", trackEnd.toInt().toString())
        value(if (value == trackEnd) "" else value.toInt().toString())
        onChange { event -> onCommit(event.value?.toFloat()) }
    }
}

@Composable
private fun OfferRow(app: WebApp, listing: Listing, copies: List<Listing>, active: Boolean, lowest: Boolean, to: Route, picking: Boolean, onHide: () -> Unit) {
    Div({
        id("offer-${listing.id}")
        classes(*listOfNotNull("offer-row", "active".takeIf { active }).toTypedArray())
    }) {
        PickBox(app, listing, picking)
        RouteLink(to, classes = listOf("offer-link"), replace = true) {
            Thumb(listing, "thumb")
            Div({ classes("offer-text") }) {
                Span({ classes("offer-title") }) { Text(listing.title.tidyTitle()) }
                Span({ classes("offer-meta") }) {
                    Text((listOf(sourceLabel(listing, copies)) + listingSpecs(listing).map { it.toString() }).joinToString(" · "))
                }
                listingFoot(listing, app.listings.views.collectAsState().value[listing.id]).takeIf { it.isNotEmpty() }?.let { foot -> Span({ classes("offer-foot") }) { Text(foot.joinToString(" · ")) } }
                SellerMarkLine(app, listing)
                NoteLine(listing.id)
            }
            Div({ classes("offer-price") }) {
                Price(listing.comparablePrice, lowest = lowest)
                if (listing.saleType == SaleType.AUCTION) Span({ classes("muted", "small") }) { Text(listing.bidCount?.let { "$it bids" } ?: "auction") }
            }
        }
        Div({ classes("offer-actions") }) { IconButton(Glyph.EyeOff, "Hide this offer") { onHide() } }
    }
}

@Composable
private fun OfferTile(app: WebApp, listing: Listing, copies: List<Listing>, active: Boolean, lowest: Boolean, to: Route, picking: Boolean, onHide: () -> Unit) {
    Div({
        id("offer-${listing.id}")
        classes(*listOfNotNull("offer-tile", "active".takeIf { active }).toTypedArray())
    }) {
        PickBox(app, listing, picking)
        RouteLink(to, classes = listOf("offer-link"), replace = true) {
            Thumb(listing, "tile-photo")
            Div({ classes("tile-text") }) {
                Price(listing.comparablePrice, lowest = lowest)
                Span({ classes("offer-title") }) { Text(listing.title.tidyTitle()) }
                Span({ classes("offer-meta") }) { Text(sourceLabel(listing, copies)) }
                SellerMarkLine(app, listing)
            }
        }
        Div({ classes("offer-actions") }) { IconButton(Glyph.EyeOff, "Hide this offer") { onHide() } }
    }
}

/** The verdict on this offer, when one was written; "Avoid" alone in the warning colour. */
@Composable
fun NoteLine(listingId: String, full: Boolean = false) {
    val notes by OfferNotes.notes.collectAsState()
    val note = notes[listingId] ?: return
    Span({ classes(*listOfNotNull("note", if (note.verdict == Verdict.AVOID) "avoid" else null, "full".takeIf { full }).toTypedArray()) }) {
        B { Text(note.verdict.label) }
        Text(" · " + note.text + if (full) "  (" + note.by + ")" else "")
    }
}

/** A saved search's name and the words it asks the markets for, changed in place. */
@Composable
private fun ChangeSearch(open: OpenSearch, name: String, onSave: (String, String) -> Unit) {
    var newName by remember { mutableStateOf(name) }
    var term by remember { mutableStateOf(open.saved?.text ?: open.view.query) }
    Form(attrs = {
        classes("change-search")
        addEventListener("submit") { it.preventDefault(); if (term.isNotBlank()) onSave(newName, term) }
    }) {
        Label(attrs = { classes("field") }) {
            Span({ classes("field-label") }) { Text("Searched for") }
            Input(InputType.Text) { classes("control"); value(term); onInput { term = it.value }; attr("autofocus", "") }
        }
        Label(attrs = { classes("field") }) {
            Span({ classes("field-label") }) { Text("Name") }
            Input(InputType.Text) { classes("control"); value(newName); onInput { newName = it.value } }
        }
        PrimaryButton("Search again", Glyph.Search, enabled = term.isNotBlank()) {}
    }
}

@Composable
fun Thumb(listing: Listing, className: String) {
    val image = listing.imageUrls.firstOrNull { it.isNotBlank() }
    if (image != null) {
        Img(src = image, alt = "") {
            classes(className)
            attr("loading", "lazy")
            attr("referrerpolicy", "no-referrer")
            onPhotoGone()
        }
    } else {
        Div({ classes(className, "no-photo") }) { Icon(Glyph.Tag, 22) }
    }
}
