package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.SearchCountries
import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCatalog
import io.github.tieo.arbay.catalog.ProductCategory
import io.github.tieo.arbay.debug.DebugSlice
import io.github.tieo.arbay.history.SearchHistoryEntry
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.platformsIn
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.history.summary
import io.github.tieo.arbay.ui.AdaptiveSheet
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import io.github.tieo.arbay.debug.debugJson

/**
 * The way into a search.
 *
 * Three things can be searched for, and they are different enough to be asked for differently: a
 * vehicle, which needs criteria a sentence cannot carry; anything else, which is words; and what is
 * being given away, which is not a search at all but a pile to sift.
 *
 * What stood here before was a wall of category tiles with brands and preset products behind them,
 * promising a catalogue the app does not have. A tile searched the same words the field does, one
 * tap later and across a narrower set of markets.
 */
@Serializable
private data class DiscoveryDebugSnapshot(val typed: String)

@Composable
fun DiscoverySheet(
    initialCategory: ProductCategory? = null,
    onDismiss: () -> Unit,
    onProductSelected: (KnownProduct) -> Unit,
    onCustomSearch: (String) -> Unit,
    onLiveSearch: ((String) -> Unit)? = null,
    // Same typed text, but as a vehicle search — a bare model name ("Sprinter", "Golf") has no
    // make in it for CarQueryResolver to recognise, so automatic car detection misses it. This is
    // the explicit override: choosing it, not guessing it.
    onLiveVehicleSearch: ((String) -> Unit)? = null,
    onFreeItems: (() -> Unit)? = null,
    onCarSearch: (() -> Unit)? = null,
    // Searches run before, most recent first, each carrying whatever it was last narrowed to.
    history: List<SearchHistoryEntry> = emptyList(),
    onOpenHistory: (SearchHistoryEntry) -> Unit = {},
    onRemoveHistory: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    // Which countries a search covers, and the sink that stores a change. Shown on the search
    // itself rather than left in settings: it decides half of what a search can possibly find.
    countries: List<String> = SearchCountries.current.countries,
    onCountriesChange: (List<String>) -> Unit = {},
) {
    var typed by remember { mutableStateOf("") }
    var pickCountries by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // Matches from the bundled catalogue, which spare the typing rather than replace it.
    val known = remember(typed) { if (typed.isBlank()) emptyList() else ProductCatalog.search(typed) }

    // What is typed but not yet searched — otherwise invisible to the debug dump, since it never
    // reaches a view model until Enter is pressed or a suggestion is tapped.
    DebugSlice("discoveryScreen") { debugJson.encodeToString(DiscoveryDebugSnapshot(typed = typed)) }

    fun search() {
        val query = typed.trim()
        if (query.isNotBlank()) onLiveSearch?.invoke(query) ?: onCustomSearch(query)
    }

    LaunchedEffect(Unit) {
        delay(300)
        runCatching { focus.requestFocus() }
    }

    if (pickCountries) {
        CountryPickerDialog(
            picked = countries,
            onPicked = onCountriesChange,
            onDismiss = { pickCountries = false },
        )
    }

    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Search",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }

        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("What are you looking for?") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )

            // The countries this search will cover, on the way in. A search that spans a continent
            // and one that stays at home are different searches, and which one is about to run was
            // only visible afterwards, in the list of markets that answered.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                Icon(
                    Icons.Outlined.Public,
                    null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (countries.isEmpty()) {
                    AssistChip(
                        onClick = { pickCountries = true },
                        label = { Text("every country", style = MaterialTheme.typography.labelSmall) },
                    )
                } else {
                    countries.forEach { country ->
                        AssistChip(
                            onClick = { pickCountries = true },
                            label = { Text(country, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                TextButton(onClick = { pickCountries = true }) {
                    Text("Change", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (typed.isNotBlank()) {
                Button(onClick = { search() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (countries.isEmpty()) "Search every market for “${typed.trim()}”"
                        else "Search ${countries.joinToString(", ")} for “${typed.trim()}”",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                onLiveVehicleSearch?.let { vehicleSearch ->
                    OutlinedButton(
                        onClick = { vehicleSearch(typed.trim()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.DirectionsCar, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Search vehicle sites for “${typed.trim()}”",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (known.isNotEmpty()) {
                    Text(
                        "or one of these, already spelled the way the markets spell it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(known) { product ->
                            KnownProductRow(product) { onProductSelected(product) }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (history.isNotEmpty()) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "Recent",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = onClearHistory) { Text("Clear") }
                            }
                        }
                        items(history, key = { it.searchQuery.text }) { entry ->
                            HistoryRow(
                                entry = entry,
                                onClick = { onOpenHistory(entry) },
                                onRemove = { onRemoveHistory(entry.searchQuery.text) },
                            )
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(
                                "Or one of the two searches that are not words:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            onCarSearch?.let {
                                WayIn(
                                    icon = Icons.Outlined.DirectionsCar,
                                    title = "Vehicle search",
                                    // Counted from the countries this search covers, not written
                                    // down: the number was fixed at 25 and the languages were a
                                    // promise the search only keeps when asked to.
                                    detail = MarketSets.platformsIn(MarketGroup.VEHICLES, countries).size.let { markets ->
                                        "Make, year, mileage, price, power and gearbox, asked of " +
                                            (if (markets == 1) "1 market" else "$markets markets") +
                                            (if (countries.isEmpty()) "" else " in ${countries.joinToString(", ")}")
                                    },
                                    onClick = it,
                                )
                            }
                            onFreeItems?.let {
                                WayIn(
                                    icon = Icons.Outlined.CardGiftcard,
                                    title = "Free items",
                                    detail = "What is being given away near you, one at a time, learning what is worth a detour",
                                    onClick = it,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One of the searches that is not a sentence. There are two, so each gets a row of its own. */
@Composable
private fun WayIn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A search run before, with the one line saying what it was last narrowed to. Tapping the row
 *  reopens it exactly as it was left; the close button forgets it without opening anything. */
@Composable
private fun HistoryRow(entry: SearchHistoryEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.History, null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    entry.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Remove from recent", modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** A product the catalogue already knows, offered while typing so a search does not depend on
 *  spelling a thing the way a particular market spells it. */
@Composable
private fun KnownProductRow(product: KnownProduct, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                product.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                product.searchQuery,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Which countries a search covers. Every country any market in the app sells in, each saying how
 *  many markets it brings, so a country that adds nothing is visibly empty rather than a guess. */
@Composable
private fun CountryPickerDialog(
    picked: List<String>,
    onPicked: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val available = remember {
        PlatformId.entries.map { MarketSets.countryOf(it) }.distinct().sorted()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            TextButton(onClick = { onPicked(emptyList()) }) { Text("Every country") }
        },
        title = { Text("Countries to search") },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                available.forEach { country ->
                    val on = country in picked
                    val markets = PlatformId.entries.count { MarketSets.countryOf(it) == country }
                    FilterChip(
                        selected = on,
                        onClick = {
                            onPicked(if (on) picked - country else picked + country)
                        },
                        label = {
                            Text("$country · $markets", style = MaterialTheme.typography.labelSmall)
                        },
                    )
                }
            }
        },
    )
}
