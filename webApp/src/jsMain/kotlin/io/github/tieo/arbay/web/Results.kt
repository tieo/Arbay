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

/**
 * One open search: what it asks, the bookmark behind it if it is saved, where its narrowing is kept
 * (on the bookmark, else in history), and what it found before it was opened.
 */
class OpenSearch(
    val view: ResultsView,
    val bookmark: TrackedProduct?,
    val saved: SearchQuery?,
    val newListingIds: Set<String>,
) {
    /** Writes a change to this search where it is kept. Never starts a crawl. */
    fun persist(app: WebApp, edit: (SearchQuery) -> SearchQuery) {
        val base = saved ?: SearchHistoryStore.baseQuery(view.query, view.platforms, view.filters, view.category)
        val next = edit(base)
        if (bookmark != null) app.products.updateProduct(bookmark.copy(searchQuery = next))
        else SearchHistoryStore.record(view.name, next)
    }
}

/** Everything the results screen and its side panels read, worked out once per change. */
class ResultsState(
    val open: OpenSearch,
    val narrowing: Narrowing,
    val narrowed: Narrowed,
    val summary: PriceSummary,
    val elsewhere: Map<String, List<Listing>>,
    val hidden: List<Hidden>,
    val markets: List<io.github.tieo.arbay.model.PlatformId>,
    val setNarrowing: (Narrowing) -> Unit,
)

/** The middle and right panes of a search. */
@Composable
fun ResultsScreen(app: WebApp, route: Route.Results) {
    val products by app.products.products.collectAsState()
    val status by app.products.status.collectAsState()
    val history by SearchHistoryStore.entries.collectAsState()

    // What a saved search found since it was last opened, read once as it opens: opening it is
    // what clears it.
    val source = route.source
    var backlog by remember(source) { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(source, status.isNotEmpty()) {
        if (source is Source.Saved && backlog == null && status.isNotEmpty()) {
            backlog = status[source.id]?.newListingIds.orEmpty().toSet()
            app.products.markOpened(source.id)
        }
        if (source is Source.Typed) SearchHistoryStore.recordOpen(source.text, source.text, null, null, MarketGroup.GENERAL)
    }

    val open: OpenSearch? = when (source) {
        is Source.Saved -> products.firstOrNull { it.id == source.id }?.let {
            OpenSearch(ResultsView.of(it), it, it.searchQuery, backlog.orEmpty())
        }
        is Source.Typed -> {
            val bookmark = products.firstOrNull { p -> p.searchQuery.category == MarketGroup.GENERAL && p.searchQuery.text.equals(source.text, ignoreCase = true) }
            val entry = history.firstOrNull { e -> e.searchQuery.category == MarketGroup.GENERAL && e.searchQuery.text.equals(source.text, ignoreCase = true) }
            OpenSearch(ResultsView.of(source.text, source.text, null, MarketGroup.GENERAL), bookmark, bookmark?.searchQuery ?: entry?.searchQuery, emptySet())
        }
        is Source.Vehicle -> {
            val bookmark = products.firstOrNull { p -> p.searchQuery.category == MarketGroup.VEHICLES && p.searchQuery.text.equals(source.text, ignoreCase = true) }
            val entry = history.firstOrNull { e -> e.searchQuery.category == MarketGroup.VEHICLES && e.searchQuery.text.equals(source.text, ignoreCase = true) }
            when {
                bookmark != null -> OpenSearch(ResultsView.of(bookmark), bookmark, bookmark.searchQuery, emptySet())
                entry != null -> OpenSearch(ResultsView.of(entry), null, entry.searchQuery, emptySet())
                else -> null
            }
        }
    }

    if (open == null) {
        Main({ classes("results") }) {
            Div({ classes("empty") }) {
                Text(if (products.isEmpty() && source is Source.Saved) "Loading…" else "This search is not here any more.")
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

    // The search's own settings go into the view model as it opens.
    val savedBlocked = open.saved?.excludeKeywords.orEmpty()
    LaunchedEffect(view.query, view.category) {
        vm.setBlockedTerms(savedBlocked)
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

    // Asked again only when what defines the crawl changes, compared by value.
    val markets = remember(view.platforms, view.isCar, shownMarkets) { marketsToAsk(view.platforms, view.isCar, shownMarkets) }
    val crawlKey = listOf(view.query, view.filters, view.aliases, markets.map { it.name }, open.saved?.location, open.saved?.radiusKm, open.saved?.reach).toString()
    LaunchedEffect(crawlKey) {
        vm.search(
            view.query, markets, view.filters, excludeKeywords = savedBlocked, aliases = view.aliases,
            reach = open.saved?.reach ?: SearchReach(),
            near = open.saved?.location,
            radiusKm = open.saved?.radiusKm?.takeIf { it > 0 },
        )
    }

    // The reader's narrowing: read out of the search once as it opens, theirs from then on.
    var narrowing by remember(view.name, view.query, view.category) { mutableStateOf<Narrowing?>(null) }
    if (narrowing == null) narrowing = Narrowing.of(open.saved)
    val current = narrowing ?: Narrowing()
    val money = Triple(DisplayCurrency.current, DisplayCurrency.rates, ImportRules.current)
    val narrowed = remember(listings, current, money, open.newListingIds) { narrow(listings, current, open.newListingIds) }
    val summary = remember(narrowed, priceHistory, money) { priceSummary(narrowed.displayed, priceHistory) }
    val hidden = remember(narrowed, fetched, marketBasis, banned, blocked, dropped) {
        hiddenListings(narrowed, current, fetched, marketBasis, banned, blocked, dropped, open.newListingIds)
    }
    val state = ResultsState(open, current, narrowed, summary, elsewhere, hidden, markets) { narrowing = it }

    val shown = narrowed.displayed
    val selected = route.listing
    KeyboardWalk(route, shown.map { it.id })

    Main({ classes("results") }) {
        Header({ classes("results-head") }) {
            Div({ classes("title-row") }) {
                Div({ classes("title-text") }) {
                    H1 { Text(view.name) }
                    val where = listOfNotNull(
                        view.query.takeIf { !it.equals(view.name, ignoreCase = true) },
                        open.saved?.location?.let { place -> "near $place" + (open.saved.radiusKm?.takeIf { it > 0 }?.let { " · $it km" } ?: "") },
                    )
                    if (where.isNotEmpty()) P({ classes("subtitle") }) { Text(where.joinToString(" · ")) }
                }
                if (view.isCar) {
                    IconButton(Glyph.Car, "Vehicle criteria", pressed = route.panel == Panel.CRITERIA) { togglePanel(route, Panel.CRITERIA) }
                }
                val bookmark = open.bookmark
                if (bookmark != null) {
                    IconButton(Glyph.Bell, "Watching and alerts", pressed = route.panel == Panel.ALERTS) { togglePanel(route, Panel.ALERTS) }
                }
                IconButton(Glyph.Refresh, "Ask the markets again") { vm.refresh(markets) }
                QuietButton(if (bookmark != null) "Saved" else "Save", Glyph.Bookmark, pressed = bookmark != null) {
                    if (bookmark != null) app.products.deleteProduct(bookmark.id)
                    else app.products.createProduct(
                        view.name.ifBlank { view.query },
                        bookmarkQuery(
                            onScreen = open.saved ?: SearchHistoryStore.baseQuery(view.query, view.platforms, view.filters, view.category),
                            text = view.query, asked = markets, category = view.category, carFilters = view.filters,
                            blockedWords = blocked, aliases = view.aliases,
                        ),
                    )
                }
            }

            MarketsLine(route, loading, total, completed, statuses)
            PriceStrip(route, summary)
            Toolbar(app, route, state, sortMode, blocked.size, hidden.sumOf { it.listings.size })
        }

        val photos = LookChoice.layout == OfferLayout.PHOTOS
        Div({ classes(if (photos) "tiles" else "rows") }) {
            if (shown.isEmpty()) {
                Div({ classes("empty") }) {
                    Text(
                        when {
                            loading -> "Waiting for the first market to answer…"
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
                if (photos) OfferTile(listing, copies, listing.id == selected, lowest, to, hide)
                else OfferRow(listing, copies, listing.id == selected, lowest, to, hide)
            }
        }
    }

    Aside({ classes("inspector") }) {
        when {
            route.panel != null -> PanelView(app, route, route.panel, state)
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
    val captcha = statuses.count { it.status == PlatformSearchStatus.CAPTCHA && it.captchaUrl != null }
    val failed = statuses.count {
        it.status in setOf(PlatformSearchStatus.ERROR, PlatformSearchStatus.TIMEOUT, PlatformSearchStatus.BLOCKED, PlatformSearchStatus.IP_BLOCKED, PlatformSearchStatus.CAPTCHA)
    }
    Button(attrs = {
        classes(*listOfNotNull("markets-line", "attention".takeIf { captcha > 0 }, "on".takeIf { route.panel == Panel.MARKETS }).toTypedArray())
        onClick { togglePanel(route, Panel.MARKETS) }
    }) {
        Icon(Glyph.Store, 16)
        Span {
            Text(
                when {
                    loading && total > 0 -> "$completed of $total markets have answered"
                    loading -> "Asking the markets"
                    else -> "${statuses.count { it.status == PlatformSearchStatus.DONE }} of ${statuses.size} markets answered"
                } + (if (failed > 0 && !loading) " · $failed could not be asked" else "") +
                    (if (captcha > 0) " · $captcha waiting for a captcha" else ""),
            )
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
                    app.listings.setSortMode(mode)
                    state.open.persist(app) { it.copy(sort = mode) }
                }
            }
        }) {
            SortMode.entries.forEach { mode -> Option(mode.name, { if (mode == sortMode) selected() }) { Text(mode.label) } }
        }

        Label(attrs = { classes("band") }) {
            BandInput(narrowed.priceRange.start, narrowed.priceMin, "Lowest price") { low ->
                val band = (low ?: narrowed.priceMin)..narrowed.priceRange.endInclusive
                state.setNarrowing(narrowing.copy(band = band))
                state.open.persist(app) { it.withBand(band, narrowed) }
            }
            Span({ classes("muted") }) { Text("–") }
            BandInput(narrowed.priceRange.endInclusive, narrowed.priceMax, "Highest price") { high ->
                val band = narrowed.priceRange.start..(high ?: narrowed.priceMax)
                state.setNarrowing(narrowing.copy(band = band))
                state.open.persist(app) { it.withBand(band, narrowed) }
            }
            Span({ classes("muted") }) { Text(DisplayCurrency.current) }
        }

        fun setConditions(next: Set<Condition>, unstated: Boolean) {
            state.setNarrowing(narrowing.copy(conditions = next, unstatedCondition = unstated))
            state.open.persist(app) { it.copy(condition = next.toList().takeIf { l -> l.isNotEmpty() }, conditionUnstated = unstated) }
        }
        Condition.entries.filter { (narrowed.conditionCounts[it] ?: 0) > 0 }.forEach { value ->
            Chip("${value.label} ${narrowed.conditionCounts[value]}", value in narrowing.conditions) {
                setConditions(if (value in narrowing.conditions) narrowing.conditions - value else narrowing.conditions + value, narrowing.unstatedCondition)
            }
        }
        // "Not stated" only matters once a condition is picked: until then every listing shows.
        narrowed.conditionCounts[null]?.takeIf { it > 0 && narrowing.conditions.isNotEmpty() }?.let { count ->
            Chip("Not stated $count", narrowing.unstatedCondition) { setConditions(narrowing.conditions, !narrowing.unstatedCondition) }
        }
        if (narrowed.saleTypeCounts.keys.filterNotNull().size > 1) {
            SaleType.entries.filter { (narrowed.saleTypeCounts[it] ?: 0) > 0 }.forEach { value ->
                Chip("${value.label} ${narrowed.saleTypeCounts[value]}", value in narrowing.saleTypes) {
                    val next = if (value in narrowing.saleTypes) narrowing.saleTypes - value else narrowing.saleTypes + value
                    state.setNarrowing(narrowing.copy(saleTypes = next))
                    state.open.persist(app) { it.copy(saleTypes = next.toList().takeIf { l -> l.isNotEmpty() }) }
                }
            }
        }
        val newCount = narrowed.allActive.count { it.id in state.open.newListingIds }
        if (newCount > 0) Chip("New $newCount", narrowing.newOnly) { state.setNarrowing(narrowing.copy(newOnly = !narrowing.newOnly)) }

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
private fun OfferRow(listing: Listing, copies: List<Listing>, active: Boolean, lowest: Boolean, to: Route, onHide: () -> Unit) {
    Div({
        id("offer-${listing.id}")
        classes(*listOfNotNull("offer-row", "active".takeIf { active }).toTypedArray())
    }) {
        RouteLink(to, classes = listOf("offer-link"), replace = true) {
            Thumb(listing, "thumb")
            Div({ classes("offer-text") }) {
                Span({ classes("offer-title") }) { Text(listing.title.tidyTitle()) }
                Span({ classes("offer-meta") }) {
                    Text((listOf(sourceLabel(listing, copies)) + listingSpecs(listing).map { it.toString() }).joinToString(" · "))
                }
                listingFoot(listing).takeIf { it.isNotEmpty() }?.let { foot -> Span({ classes("offer-foot") }) { Text(foot.joinToString(" · ")) } }
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
private fun OfferTile(listing: Listing, copies: List<Listing>, active: Boolean, lowest: Boolean, to: Route, onHide: () -> Unit) {
    Div({
        id("offer-${listing.id}")
        classes(*listOfNotNull("offer-tile", "active".takeIf { active }).toTypedArray())
    }) {
        RouteLink(to, classes = listOf("offer-link"), replace = true) {
            Thumb(listing, "tile-photo")
            Div({ classes("tile-text") }) {
                Price(listing.comparablePrice, lowest = lowest)
                Span({ classes("offer-title") }) { Text(listing.title.tidyTitle()) }
                Span({ classes("offer-meta") }) { Text(sourceLabel(listing, copies)) }
            }
        }
        Div({ classes("offer-actions") }) { IconButton(Glyph.EyeOff, "Hide this offer") { onHide() } }
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
        }
    } else {
        Div({ classes(className, "no-photo") }) { Icon(Glyph.Tag, 22) }
    }
}
