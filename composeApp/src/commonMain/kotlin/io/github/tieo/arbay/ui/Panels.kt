package io.github.tieo.arbay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.comparablePrice
import io.github.tieo.arbay.format
import io.github.tieo.arbay.model.AutoFetchSettings
import io.github.tieo.arbay.model.DropReason
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.PlatformSearchStatus
import io.github.tieo.arbay.model.displayName
import io.github.tieo.arbay.model.tidyTitle
import io.github.tieo.arbay.navigation.Panel
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.openBrowser
import io.github.tieo.arbay.results.HiddenKind
import io.github.tieo.arbay.results.ResultsState
import io.github.tieo.arbay.results.WATCH_INTERVALS
import io.github.tieo.arbay.results.blockWord
import io.github.tieo.arbay.results.clearBand
import io.github.tieo.arbay.results.coveredMarkets
import io.github.tieo.arbay.results.isProblem
import io.github.tieo.arbay.results.newRule
import io.github.tieo.arbay.results.saidWhat
import io.github.tieo.arbay.results.setAliases
import io.github.tieo.arbay.results.setOtherWords
import io.github.tieo.arbay.results.showBothSaleTypes
import io.github.tieo.arbay.results.showEveryCondition
import io.github.tieo.arbay.results.toggleMarket
import io.github.tieo.arbay.results.toggleNewOnly
import io.github.tieo.arbay.results.toggleSuggestedWord
import io.github.tieo.arbay.results.unblockWord
import io.github.tieo.arbay.results.wordThatCaught

/** A closer look at an open search, as a sheet over its results; dismissing it is a step back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelSheet(session: Session, route: Route.Results, panel: Panel, state: ResultsState) {
    val nav = LocalNavigator.current
    ModalBottomSheet(
        onDismissRequest = { nav.back() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = panel == Panel.CRITERIA || panel == Panel.HIDDEN || panel == Panel.WRITE),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        PanelBody(session, route, panel, state, Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding())
    }
}

/** What a panel holds, apart from the sheet it opens in. */
@Composable
fun PanelBody(session: Session, route: Route.Results, panel: Panel, state: ResultsState, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = arbay.pad).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(arbay.gap),
    ) {
        when (panel) {
            Panel.PRICES -> PricesPanel(session, route, state)
            Panel.HIDDEN -> HiddenPanel(session, route, state)
            Panel.MARKETS -> MarketsPanel(session, state)
            Panel.WORDS -> WordsPanel(session, state)
            Panel.ALERTS -> AlertsPanel(session, state)
            Panel.CRITERIA -> CriteriaPanel(session, state)
            Panel.WRITE -> WritePanel(session, route, state)
        }
    }
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleLarge)

/** Whether a price is good: the spread drawn large, the figures, what the thing sold for (asked
 *  only when wanted), and the cheapest offers. */
@Composable
private fun PricesPanel(session: Session, route: Route.Results, state: ResultsState) {
    val nav = LocalNavigator.current
    val s = state.summary
    val soldLoading by session.listings.soldLoading.collectAsState()
    Title("Price")
    if (s.count == 0) { Muted("No offers to price yet."); return }
    Spread(s, Modifier.fillMaxWidth(), height = 96.dp)
    Row(Modifier.fillMaxWidth()) {
        s.cheapest?.let { PriceText(it, lowest = true, style = MaterialTheme.typography.bodySmall) }
        Box(Modifier.weight(1f))
        s.dearest?.let { PriceText(it, style = MaterialTheme.typography.bodySmall) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Figure("Cheapest", s.cheapest?.format() ?: "–", Modifier.weight(1f), lowest = true)
        Figure("Middle", s.middle?.format() ?: "–", Modifier.weight(1f))
        Figure("Offers", "${s.count}", Modifier.weight(1f))
    }
    HorizontalDivider(color = arbay.line)
    Text("What it sold for", style = MaterialTheme.typography.titleMedium)
    val sold = s.sold
    when {
        sold != null -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Figure("Middle sold price", sold.middle.format(), Modifier.weight(1f))
            Figure("Sales", "${sold.count}", Modifier.weight(1f))
        }
        soldLoading -> Muted("Asking the markets that publish sales")
        else -> OutlinedButton(onClick = { session.listings.searchSold() }) { Text("Ask what it sold for") }
    }
    val cheapest = state.shown.filter { it.id in s.cheapestIds }
    if (cheapest.isNotEmpty()) {
        HorizontalDivider(color = arbay.line)
        Text("The cheapest", style = MaterialTheme.typography.titleMedium)
        cheapest.take(3).forEach { l -> MiniOffer(l, lowest = true) { nav.replace(route.copy(panel = null, listing = l.id)) } }
    }
}

@Composable
private fun MiniOffer(listing: Listing, lowest: Boolean = false, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Photo(listing.imageUrls.firstOrNull { it.isNotBlank() }, Modifier.size(44.dp).clip(MaterialTheme.shapes.small))
        Text(listing.title.tidyTitle(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        PriceText(listing.comparablePrice, lowest = lowest, style = MaterialTheme.typography.bodyMedium)
        trailing?.invoke()
    }
}

/** Every listing missing from the results, grouped by what took it, each group with the one thing
 *  that puts it back. */
@Composable
private fun HiddenPanel(session: Session, route: Route.Results, state: ResultsState) {
    val nav = LocalNavigator.current
    val vm = session.listings
    val products = session.products
    val blocked by vm.blockedTerms.collectAsState()
    Title("Not shown")
    if (state.hidden.isEmpty()) Muted("Everything the markets sent is on the list.")
    state.hidden.forEach { group ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${group.listings.size} · ${group.label}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            when (val kind = group.kind) {
                HiddenKind.YouHid -> TextButton(onClick = { vm.unbanAll() }) { Text("Put all back") }
                HiddenKind.BlockedWords -> TextButton(onClick = { nav.replace(route.copy(panel = Panel.WORDS)) }) { Text("Edit the words") }
                HiddenKind.PriceBand -> TextButton(onClick = { state.clearBand(products) }) { Text("Widen it") }
                HiddenKind.Condition -> TextButton(onClick = { state.showEveryCondition(products) }) { Text("Show every condition") }
                HiddenKind.SaleType -> TextButton(onClick = { state.showBothSaleTypes(products) }) { Text("Show both") }
                HiddenKind.NotNew -> TextButton(onClick = { state.toggleNewOnly() }) { Text("Show everything") }
                HiddenKind.LikelyScam -> {}
                is HiddenKind.Search -> if (kind.reason == DropReason.VEHICLE_CRITERIA) {
                    TextButton(onClick = { nav.replace(route.copy(panel = Panel.CRITERIA)) }) { Text("Edit the criteria") }
                }
            }
        }
        Muted(group.why)
        group.listings.take(30).forEach { l ->
            MiniOffer(l, onClick = { openBrowser(l.url) }) {
                when (group.kind) {
                    HiddenKind.YouHid -> IconButton(onClick = { vm.unban(l) }) { Icon(Icons.Outlined.Undo, "Put back") }
                    HiddenKind.BlockedWords -> wordThatCaught(l, blocked)?.let { word ->
                        IconButton(onClick = { state.unblockWord(products, vm, word) }) { Icon(Icons.Outlined.Undo, "Unblock \"$word\"") }
                    }
                    else -> {}
                }
            }
        }
        if (group.listings.size > 30) Muted("and ${group.listings.size - 30} more")
        HorizontalDivider(color = arbay.line)
    }
}

/** What each market answered, and which the reader looks at: ticking one narrows what is shown and
 *  makes sure it is asked; none ticked is all of them. A captcha is solved right there. */
@Composable
private fun MarketsPanel(session: Session, state: ResultsState) {
    val vm = session.listings
    val statuses by vm.platformStatuses.collectAsState()
    val shown by vm.shownMarkets.collectAsState()
    val fetched by vm.fetched.collectAsState()
    val kept = state.shown.groupingBy { it.platformId }.eachCount()
    val sent = fetched.groupingBy { it.platformId }.eachCount()
    val every = (statuses.mapNotNull { s -> runCatching { PlatformId.valueOf(s.platformId) }.getOrNull() } + coveredMarkets(state.open.view.isCar)).distinct()
    Title("Markets")
    every.sortedWith(compareByDescending<PlatformId> { kept[it] ?: 0 }.thenBy { it.displayName }).forEach { platform ->
        val status = statuses.firstOrNull { it.platformId == platform.name }
        val count = kept[platform] ?: 0
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice(platform.displayName, platform in shown) { state.toggleMarket(session.products, vm, platform) }
            Column(Modifier.weight(1f)) {
                status?.saidWhat(count, hiddenByWords = count == 0 && (sent[platform] ?: 0) > 0)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (status.isProblem()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Muted(if (status == null) "not asked" else "$count", style = MaterialTheme.typography.bodyMedium)
        }
        status?.takeIf { it.status == PlatformSearchStatus.CAPTCHA }?.captchaUrl?.let { url ->
            OutlinedButton(onClick = { openBrowser(url) }) { Text("Solve the captcha for ${platform.displayName}") }
        }
    }
}

/** The words that decide what a search finds: words that keep a listing out, other names that count
 *  as the same search, and the other words the markets themselves offer. */
@Composable
private fun WordsPanel(session: Session, state: ResultsState) {
    val vm = session.listings
    val products = session.products
    val blocked by vm.blockedTerms.collectAsState()
    val otherWords by vm.otherWords.collectAsState()
    val picked by vm.pickedWords.collectAsState()
    val reach by vm.searchReach.collectAsState()
    val aliases = state.open.view.aliases
    Title("Words")
    Text("Keep out listings with", style = MaterialTheme.typography.titleMedium)
    WordList(blocked, "Block a word", onRemove = { state.unblockWord(products, vm, it) }, onAdd = { state.blockWord(products, vm, it) })
    HorizontalDivider(color = arbay.line)
    Text("Also counts as this search", style = MaterialTheme.typography.titleMedium)
    WordList(aliases, "Another name for it", onRemove = { state.setAliases(products, aliases - it) }, onAdd = { state.setAliases(products, aliases + it) })
    HorizontalDivider(color = arbay.line)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Ask in more words", style = MaterialTheme.typography.titleMedium)
            Muted("The markets' own other names for it")
        }
        Switch(reach.otherWords, { state.setOtherWords(products, vm, it) })
    }
    if (otherWords.isNotEmpty()) Choices {
        otherWords.forEach { term ->
            Choice(term.term + (term.added?.let { " +$it" } ?: ""), picked.any { it.equals(term.term, true) }) {
                state.toggleSuggestedWord(products, vm, term.term)
            }
        }
    }
}

@Composable
private fun WordList(words: List<String>, adding: String, onRemove: (String) -> Unit, onAdd: (String) -> Unit) {
    var draft by remember { mutableStateOf("") }
    if (words.isNotEmpty()) Choices {
        words.forEach { word ->
            InputChip(selected = false, onClick = { onRemove(word) }, label = { Text(word) }, trailingIcon = { Icon(Icons.Outlined.Close, "Remove \"$word\"", Modifier.size(16.dp)) })
        }
    }
    OutlinedTextField(
        draft, { draft = it }, Modifier.fillMaxWidth(),
        placeholder = { Text(adding) }, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { draft.trim().takeIf { it.isNotEmpty() }?.let(onAdd); draft = "" }),
    )
}

/** What one saved search does on its own: whether it looks again in the background, and which finds
 *  are worth a notification. */
@Composable
private fun AlertsPanel(session: Session, state: ResultsState) {
    val products by session.products.products.collectAsState()
    val bookmark = products.firstOrNull { it.id == state.open.bookmark?.id }
    Title("Watching")
    if (bookmark == null) { Muted("Save this search to have it watched."); return }
    val auto = bookmark.autoFetch
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Look again on its own", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(auto.enabled, { session.products.setAutoFetch(bookmark, auto.copy(enabled = it)) })
    }
    if (auto.enabled) Choices {
        WATCH_INTERVALS.forEach { (minutes, label) ->
            Choice("every $label", auto.intervalMinutes == minutes) {
                session.products.setAutoFetch(bookmark, AutoFetchSettings(enabled = true, intervalMinutes = minutes))
            }
        }
    }
    HorizontalDivider(color = arbay.line)
    Text("Tell me when a find is", style = MaterialTheme.typography.titleMedium)
    bookmark.notificationSubfilters.forEach { rule ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rule.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(rule.enabled, { on ->
                session.products.setNotificationSubfilters(bookmark, bookmark.notificationSubfilters.map { if (it.id == rule.id) it.copy(enabled = on) else it })
            })
            IconButton(onClick = { session.products.setNotificationSubfilters(bookmark, bookmark.notificationSubfilters.filterNot { it.id == rule.id }) }) {
                Icon(Icons.Outlined.Close, "Remove")
            }
        }
    }
    var max by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf<String?>(null) }
    var words by remember { mutableStateOf("") }
    Panel {
        OutlinedTextField(max, { max = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Up to €") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        Choices { listOf(null to "Any condition", "NEW" to "New", "USED" to "Used").forEach { (v, l) -> Choice(l, condition == v) { condition = v } } }
        OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth(), label = { Text("Has one of these words") }, singleLine = true)
        TextButton(enabled = max.isNotEmpty() || condition != null || words.isNotBlank(), onClick = {
            session.products.setNotificationSubfilters(bookmark, bookmark.notificationSubfilters + newRule(max.toIntOrNull(), condition, words))
            max = ""; condition = null; words = ""
        }) { Text("Add this rule") }
    }
}
