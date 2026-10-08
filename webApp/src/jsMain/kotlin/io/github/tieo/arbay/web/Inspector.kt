package io.github.tieo.arbay.web

import io.github.tieo.arbay.results.ResultsState
import io.github.tieo.arbay.navigation.Panel
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.navigation.Source
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.ImportRules
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.format
import io.github.tieo.arbay.model.AUCTION_LEAD_CHOICES
import io.github.tieo.arbay.model.AutoFetchSettings
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.NotificationSubfilter
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.PlatformSearchStatus
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.displayName
import io.github.tieo.arbay.model.importVat
import io.github.tieo.arbay.model.summaryText
import io.github.tieo.arbay.model.tidyTitle
import io.github.tieo.arbay.results.HiddenKind
import io.github.tieo.arbay.results.coveredMarkets
import io.github.tieo.arbay.results.detailSpecs
import io.github.tieo.arbay.results.isProblem
import io.github.tieo.arbay.results.label
import io.github.tieo.arbay.results.saidWhat
import io.github.tieo.arbay.results.sourceLabel
import io.github.tieo.arbay.results.wordThatCaught
import io.github.tieo.arbay.results.blockWord
import io.github.tieo.arbay.results.clearBand
import io.github.tieo.arbay.results.setAliases
import io.github.tieo.arbay.results.setOtherWords
import io.github.tieo.arbay.results.showBothSaleTypes
import io.github.tieo.arbay.results.showEveryCondition
import io.github.tieo.arbay.results.toggleMarket
import io.github.tieo.arbay.results.toggleNewOnly
import io.github.tieo.arbay.results.toggleSuggestedWord
import io.github.tieo.arbay.results.unblockWord
import io.github.tieo.arbay.results.WATCH_INTERVALS
import io.github.tieo.arbay.results.againstMiddle
import io.github.tieo.arbay.results.copyLabel
import io.github.tieo.arbay.results.importVatNote
import io.github.tieo.arbay.results.newRule
import io.github.tieo.arbay.results.offerFacts
import io.github.tieo.arbay.results.readOffer
import io.github.tieo.arbay.state.OfferNotes
import io.github.tieo.arbay.viewmodel.PlatformStatus
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Article
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.Img
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** The head of a side panel: what it is, and the way to close it. */
@Composable
private fun PanelHead(route: Route.Results, title: String) {
    Div({ classes("panel-head") }) {
        H2 { Text(title) }
        IconButton(Glyph.Close, "Close") { Router.replace(route.copy(panel = null)) }
    }
}

@Composable
fun PanelView(app: WebApp, route: Route.Results, panel: Panel, state: ResultsState) {
    when (panel) {
        Panel.PRICES -> PricesPanel(app, route, state)
        Panel.HIDDEN -> HiddenPanel(app, route, state)
        Panel.MARKETS -> MarketsPanel(app, route, state)
        Panel.WORDS -> WordsPanel(app, route, state)
        Panel.ALERTS -> AlertsPanel(app, route, state)
        Panel.CRITERIA -> CriteriaPanel(app, route, state)
        Panel.WRITE -> WritePanel(app, route, state)
    }
}

// ── The price picture ───────────────────────────────────────────────

/** Whether a price is good: the spread drawn large, the figures, and what the thing sold for,
 *  asked of the markets that publish sales only when the reader wants it. */
@Composable
fun PricesPanel(app: WebApp, route: Route.Results, state: ResultsState) {
    val summary = state.summary
    val soldLoading by app.listings.soldLoading.collectAsState()
    Div({ classes("panel") }) {
        if (route.panel == Panel.PRICES) PanelHead(route, "Price") else H2({ classes("panel-title") }) { Text("Price") }
        if (summary.count == 0) {
            P({ classes("muted") }) { Text("No offers to price yet.") }
            return@Div
        }
        Spread(summary, tall = true)
        Div({ classes("spread-axis") }) {
            summary.cheapest?.let { Price(it, lowest = true) }
            summary.dearest?.let { Price(it) }
        }
        Div({ classes("figures") }) {
            Figure("Cheapest", summary.cheapest?.format())
            Figure("Middle", summary.middle?.format())
            Figure("Dearest", summary.dearest?.format())
            Figure("Offers", summary.count.toString())
        }
        H3 { Text("What it sold for") }
        val sold = summary.sold
        when {
            sold != null -> Div({ classes("figures") }) {
                Figure("Middle sold price", sold.middle.format())
                Figure("Sales", sold.count.toString())
            }
            soldLoading -> P({ classes("muted") }) { Text("Asking the markets that publish sales…") }
            else -> QuietButton("Ask what it sold for", Glyph.Chart) { app.listings.searchSold() }
        }
        val cheapest = state.narrowed.displayed.filter { it.id in summary.cheapestIds }
        if (cheapest.isNotEmpty()) {
            H3 { Text("The cheapest") }
            cheapest.take(3).forEach { listing -> MiniOffer(route, listing, state) }
        }
    }
}

@Composable
private fun Figure(label: String, value: String?) {
    Div({ classes("figure") }) {
        Span({ classes("figure-label") }) { Text(label) }
        Span({ classes("figure-value") }) { Text(value ?: "–") }
    }
}

@Composable
private fun MiniOffer(route: Route.Results, listing: Listing, state: ResultsState) {
    RouteLink(route.copy(listing = listing.id, panel = null), classes = listOf("mini-offer"), replace = true) {
        Thumb(listing, "mini-thumb")
        Span({ classes("mini-title") }) { Text(listing.title.tidyTitle()) }
        Price(listing.comparablePrice, lowest = listing.id in state.summary.cheapestIds)
    }
}

// ── Everything not shown ────────────────────────────────────────────

/** Every listing missing from the results, grouped by what took it, each group with the one
 *  thing that puts it back. */
@Composable
private fun HiddenPanel(app: WebApp, route: Route.Results, state: ResultsState) {
    val vm = app.listings
    val blocked by vm.blockedTerms.collectAsState()
    Div({ classes("panel") }) {
        PanelHead(route, "Not shown")
        if (state.hidden.isEmpty()) P({ classes("muted") }) { Text("Everything the markets sent is on the list.") }
        state.hidden.forEach { group ->
            Div({ classes("hidden-group") }) {
                Div({ classes("hidden-head") }) {
                    H3 { Text("${group.listings.size} · ${group.label}") }
                    when (val kind = group.kind) {
                        HiddenKind.YouHid -> QuietButton("Put all back") { vm.unbanAll() }
                        HiddenKind.BlockedWords -> QuietButton("Edit the words") { Router.replace(route.copy(panel = Panel.WORDS)) }
                        HiddenKind.PriceBand -> QuietButton("Widen it") { state.clearBand(app.products) }
                        HiddenKind.Condition -> QuietButton("Show every condition") { state.showEveryCondition(app.products) }
                        HiddenKind.SaleType -> QuietButton("Show both") { state.showBothSaleTypes(app.products) }
                        HiddenKind.NotNew -> QuietButton("Show everything") { state.toggleNewOnly() }
                        HiddenKind.LikelyScam -> {}
                        is HiddenKind.Search -> if (kind.reason == io.github.tieo.arbay.model.DropReason.VEHICLE_CRITERIA) {
                            QuietButton("Edit the criteria") { Router.replace(route.copy(panel = Panel.CRITERIA)) }
                        }
                    }
                }
                P({ classes("muted") }) { Text(group.why) }
                group.listings.take(50).forEach { listing ->
                    Div({ classes("hidden-row") }) {
                        Thumb(listing, "mini-thumb")
                        A(href = listing.url, attrs = { classes("mini-title"); attr("target", "_blank"); attr("rel", "noopener noreferrer") }) {
                            Text(listing.title.tidyTitle())
                        }
                        Price(listing.comparablePrice)
                        when (group.kind) {
                            HiddenKind.YouHid -> IconButton(Glyph.Undo, "Put back") { vm.unban(listing) }
                            HiddenKind.BlockedWords -> wordThatCaught(listing, blocked)?.let { word ->
                                IconButton(Glyph.Undo, "Unblock \"$word\"") { state.unblockWord(app.products, vm, word) }
                            }
                            else -> {}
                        }
                    }
                }
                if (group.listings.size > 50) P({ classes("muted") }) { Text("and ${group.listings.size - 50} more") }
            }
        }
    }
}

// ── Markets ─────────────────────────────────────────────────────────

/** What each market answered, and which of them the reader is looking at. Ticking one narrows
 *  what is shown and makes sure it is asked; none ticked is all of them. */
@Composable
private fun MarketsPanel(app: WebApp, route: Route.Results, state: ResultsState) {
    val vm = app.listings
    val statuses by vm.platformStatuses.collectAsState()
    val shown by vm.shownMarkets.collectAsState()
    val fetched by vm.fetched.collectAsState()
    val keptByMarket = state.narrowed.displayed.groupingBy { it.platformId }.eachCount()
    val sentByMarket = fetched.groupingBy { it.platformId }.eachCount()
    val covered = coveredMarkets(state.open.view.isCar)
    val everyMarket = (statuses.mapNotNull { s -> runCatching { PlatformId.valueOf(s.platformId) }.getOrNull() } + covered).distinct()
    Div({ classes("panel") }) {
        PanelHead(route, "Markets")
        everyMarket.sortedWith(compareByDescending<PlatformId> { keptByMarket[it] ?: 0 }.thenBy { it.displayName }).forEach { platform ->
            val status = statuses.firstOrNull { it.platformId == platform.name }
            val kept = keptByMarket[platform] ?: 0
            val picked = platform in shown
            Div({ classes(*listOfNotNull("market-row", "problem".takeIf { status?.isProblem() == true }).toTypedArray()) }) {
                Chip(platform.displayName, picked) { state.toggleMarket(app.products, vm, platform) }
                Span({ classes("market-count") }) { Text(if (status == null) "not asked" else "$kept") }
                val note = status?.saidWhat(kept, hiddenByWords = kept == 0 && (sentByMarket[platform] ?: 0) > 0)
                note?.let { Span({ classes("muted", "small") }) { Text(it) } }
                status?.takeIf { it.status == PlatformSearchStatus.CAPTCHA }?.captchaUrl?.let { url ->
                    A(href = url, attrs = { classes("button"); attr("target", "_blank"); attr("rel", "noopener") }) {
                        Text("Solve the captcha")
                    }
                }
            }
        }
    }
}

// ── Words ───────────────────────────────────────────────────────────

/** The words that decide what a search finds: words that keep a listing out, other spellings
 *  that count as the same search, and the other words the markets themselves offer. */
@Composable
private fun WordsPanel(app: WebApp, route: Route.Results, state: ResultsState) {
    val vm = app.listings
    val blocked by vm.blockedTerms.collectAsState()
    val otherWords by vm.otherWords.collectAsState()
    val picked by vm.pickedWords.collectAsState()
    val reach by vm.searchReach.collectAsState()
    val view = state.open.view
    Div({ classes("panel") }) {
        PanelHead(route, "Words")

        H3 { Text("Keep out listings with") }
        WordList(blocked, onRemove = { state.unblockWord(app.products, vm, it) }, onAdd = { state.blockWord(app.products, vm, it) }, adding = "Block a word")

        H3 { Text("Also counts as this search") }
        WordList(view.aliases, onRemove = { state.setAliases(app.products, view.aliases - it) }, onAdd = { state.setAliases(app.products, view.aliases + it) }, adding = "Another name for it")

        H3 { Text("Ask in more words") }
        Switch("The markets' own other names for it", reach.otherWords) { state.setOtherWords(app.products, vm, it) }
        if (otherWords.isNotEmpty()) {
            Div({ classes("chips") }) {
                otherWords.forEach { term ->
                    val on = picked.any { it.equals(term.term, ignoreCase = true) }
                    Span({ attr("title", term.why) }) {
                        Chip(term.term + (term.added?.let { " +$it" } ?: ""), on) { state.toggleSuggestedWord(app.products, vm, term.term) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WordList(words: List<String>, onRemove: (String) -> Unit, onAdd: (String) -> Unit, adding: String) {
    var draft by remember { mutableStateOf("") }
    Div({ classes("chips") }) {
        words.forEach { word ->
            Span({ classes("word") }) {
                Text(word)
                IconButton(Glyph.Close, "Remove \"$word\"") { onRemove(word) }
            }
        }
    }
    Form(attrs = {
        classes("inline-form")
        addEventListener("submit") { event ->
            event.preventDefault()
            draft.trim().takeIf { it.isNotEmpty() }?.let(onAdd)
            draft = ""
        }
    }) {
        Input(InputType.Text) {
            classes("control")
            placeholder(adding)
            attr("aria-label", adding)
            value(draft)
            onInput { draft = it.value }
        }
    }
}

// ── Watching and alerts ─────────────────────────────────────────────

/** What one saved search does on its own: whether it re-runs in the background, and which finds
 *  are worth a notification. */
@Composable
private fun AlertsPanel(app: WebApp, route: Route.Results, state: ResultsState) {
    val products by app.products.products.collectAsState()
    val bookmark = products.firstOrNull { it.id == state.open.bookmark?.id }
    Div({ classes("panel") }) {
        PanelHead(route, "Watching")
        if (bookmark == null) {
            P({ classes("muted") }) { Text("Save this search to have it watched.") }
            return@Div
        }
        val auto = bookmark.autoFetch
        Switch("Look again on its own", auto.enabled) { on -> app.products.setAutoFetch(bookmark, auto.copy(enabled = on)) }
        if (auto.enabled) {
            Div({ classes("chips") }) {
                WATCH_INTERVALS.forEach { (minutes, label) ->
                    Chip("every $label", auto.intervalMinutes == minutes) {
                        app.products.setAutoFetch(bookmark, AutoFetchSettings(enabled = true, intervalMinutes = minutes))
                    }
                }
            }
        }

        H3 { Text("Tell me when a find is") }
        bookmark.notificationSubfilters.forEach { rule ->
            Div({ classes("rule") }) {
                Switch(rule.displayName, rule.enabled) { on ->
                    app.products.setNotificationSubfilters(bookmark, bookmark.notificationSubfilters.map { if (it.id == rule.id) it.copy(enabled = on) else it })
                }
                IconButton(Glyph.Close, "Remove") {
                    app.products.setNotificationSubfilters(bookmark, bookmark.notificationSubfilters.filterNot { it.id == rule.id })
                }
            }
        }
        RuleForm { rule -> app.products.setNotificationSubfilters(bookmark, bookmark.notificationSubfilters + rule) }
    }
}

@Composable
private fun RuleForm(onAdd: (NotificationSubfilter) -> Unit) {
    var max by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf<String?>(null) }
    var words by remember { mutableStateOf("") }
    Form(attrs = {
        classes("rule-form")
        addEventListener("submit") { event ->
            event.preventDefault()
            onAdd(newRule(max.toIntOrNull(), condition, words))
            max = ""; condition = null; words = ""
        }
    }) {
        Input(InputType.Number) {
            classes("control"); placeholder("Up to €"); attr("aria-label", "Up to this price")
            value(max); onInput { max = it.value?.toString().orEmpty() }
        }
        Div({ classes("chips") }) {
            listOf(null to "Any", "NEW" to "New", "USED" to "Used").forEach { (value, label) ->
                Chip(label, condition == value) { condition = value }
            }
        }
        Input(InputType.Text) {
            classes("control"); placeholder("Has one of these words"); attr("aria-label", "Has one of these words")
            value(words); onInput { words = it.value }
        }
        PrimaryButton("Add", Glyph.Bell) {}
    }
}

// ── One listing ─────────────────────────────────────────────────────

/** The offer chosen in the middle pane: its photos, price, every fact its market published, its
 *  description, and a way to each market carrying it. */
@Composable
fun DetailPane(app: WebApp, route: Route.Results, id: String, state: ResultsState) {
    val listings by app.listings.listings.collectAsState()
    val fetched by app.listings.fetched.collectAsState()
    val live = listings.firstOrNull { it.id == id } ?: fetched.firstOrNull { it.id == id }
    // An offer whose market has taken it down is still here as it was found.
    var archived by remember(id) { mutableStateOf<Listing?>(null) }
    LaunchedEffect(id, live == null) { if (live == null) archived = app.client.getArchivedListing(id) }
    val listing = live ?: archived
    if (listing == null) {
        Div({ classes("empty") }) { Text("Looking for this offer…") }
        return
    }
    ListingView(app, route, listing, state.elsewhere[listing.id].orEmpty(), isArchived = live == null, lowest = listing.id in state.summary.cheapestIds, state = state)
}

@Composable
private fun ListingView(app: WebApp, route: Route.Results, listing: Listing, copies: List<Listing>, isArchived: Boolean, lowest: Boolean, state: ResultsState) {
    var fromItsPage by remember(listing.id) { mutableStateOf<ListingDetail?>(null) }
    var reading by remember(listing.id) { mutableStateOf(false) }
    LaunchedEffect(listing.id) {
        if (isArchived || listing.url.isBlank()) return@LaunchedEffect
        reading = true
        fromItsPage = app.client.listingDetail(listing)
        reading = false
    }
    val page = readOffer(listing, fromItsPage)
    val vehicle = page.vehicle
    val description = page.description

    Article({ classes("listing") }) {
        Div({ classes("panel-head") }) {
            Span({ classes("muted", "small") }) { Text(sourceLabel(listing, copies)) }
            IconButton(Glyph.Close, "Close") { Router.replace(route.copy(listing = null)) }
        }
        if (isArchived) P({ classes("notice") }) { Text("No longer on ${listing.platformId.displayName}. This is the copy kept when it was found.") }
        H2 { Text(listing.title.tidyTitle()) }
        Gallery(listing)

        Div({ classes("price-block") }) {
            Price(listing.comparablePrice, lowest = lowest, big = true)
            listing.oldPrice?.let { Span({ classes("old-price") }) { Text(it.format()) } }
            state.summary.againstMiddle(listing)?.let { Span({ classes("muted") }) { Text(it) } }
        }
        importVatNote(listing)?.let { P({ classes("muted", "small") }) { Text(it) } }
        if (OfferNotes.notes.collectAsState().value[listing.id] != null) Div({ classes("note-panel") }) {
            NoteLine(listing.id, full = true)
            QuietButton("Remove the note") { OfferNotes.set(listing.id, null) }
        }

        Div({ classes("actions") }) {
            A(href = listing.url, attrs = { classes("primary", "link-button"); attr("target", "_blank"); attr("rel", "noopener noreferrer") }) {
                Icon(Glyph.External); Text("Open on ${listing.platformId.displayName}")
            }
            copies.forEach { copy ->
                A(href = copy.url, attrs = { classes("quiet", "link-button"); attr("target", "_blank"); attr("rel", "noopener noreferrer") }) {
                    Icon(Glyph.External)
                    Text(copyLabel(copy, listing))
                }
            }
            if (!isArchived) SellerAction(app, route, listing)
        }

        if (listing.saleType == SaleType.AUCTION && listing.auctionEndsAt != null && !isArchived) AuctionReminder(app, listing)

        Div({ classes("facts") }) {
            offerFacts(listing, page.place).forEach { fact -> Span({ classes("fact") }) { Text(fact) } }
        }

        vehicle?.let { v ->
            val specs = detailSpecs(v)
            if (specs.isNotEmpty()) {
                H3 { Text("What the market says it is") }
                Div({ classes("specs") }) {
                    specs.forEach { spec ->
                        val stated = spec.stated(v)
                        Span({ classes("spec-label") }) { Text(spec.label) }
                        Span({
                            classes(*listOfNotNull("spec-value", "read".takeIf { !stated }).toTypedArray())
                            if (!stated) attr("title", "Read out of the words, not stated by the market")
                        }) { Text(if (stated) spec.value else "~${spec.value}") }
                    }
                }
            }
        }

        description?.takeIf { it.isNotBlank() }?.let {
            H3 { Text("Description") }
            P({ classes("description") }) { Text(it) }
        }
        if (reading) P({ classes("muted", "small") }) { Text("Reading the rest off the ad…") }
    }
}

/** An auction runs out whether or not the page is open, so the useful thing is to be told a chosen
 *  stretch before it does, while a bid can still be made. */
@Composable
private fun AuctionReminder(app: WebApp, listing: Listing) {
    var lead by remember(listing.id) { mutableStateOf<Int?>(null) }
    Div({ classes("reminder") }) {
        Icon(Glyph.Clock, 16)
        Span({ classes("muted") }) { Text("Remind me") }
        AUCTION_LEAD_CHOICES.forEach { (minutes, label) ->
            Chip(label, lead == minutes) {
                lead = if (lead == minutes) null else minutes
                app.listings.remindBeforeAuction(listing, lead)
            }
        }
    }
}

/** The photos large, one at a time, with every one beside it to pick from. */
@Composable
private fun Gallery(listing: Listing) {
    val images = listing.imageUrls.filter { it.isNotBlank() }
    if (images.isEmpty()) return
    var shown by remember(listing.id) { mutableStateOf(0) }
    Div({ classes("gallery") }) {
        Img(src = images[shown.coerceIn(0, images.lastIndex)], alt = listing.title) {
            classes("hero")
            attr("referrerpolicy", "no-referrer")
            onPhotoGone()
        }
        if (images.size > 1) {
            Div({ classes("strip") }) {
                images.forEachIndexed { index, url ->
                    Button(attrs = {
                        classes(*listOfNotNull("strip-item", "on".takeIf { index == shown }).toTypedArray())
                        attr("aria-label", "Photo ${index + 1} of ${images.size}")
                        onClick { shown = index }
                    }) {
                        Img(src = url, alt = "") { attr("loading", "lazy"); attr("referrerpolicy", "no-referrer"); onPhotoGone() }
                    }
                }
            }
        }
    }
}
