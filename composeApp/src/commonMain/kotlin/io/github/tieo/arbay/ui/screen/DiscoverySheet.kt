package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.DirectionsCar
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
import io.github.tieo.arbay.catalog.KnownProduct
import io.github.tieo.arbay.catalog.ProductCatalog
import io.github.tieo.arbay.catalog.ProductCategory
import io.github.tieo.arbay.debug.DebugSlice
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
    onFreeItems: (() -> Unit)? = null,
    onCarSearch: (() -> Unit)? = null,
) {
    var typed by remember { mutableStateOf("") }
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

            if (typed.isNotBlank()) {
                Button(onClick = { search() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Search every market for “${typed.trim()}”",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                Text(
                    "Or one of the two searches that are not words:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onCarSearch?.let {
                    WayIn(
                        icon = Icons.Outlined.DirectionsCar,
                        title = "Vehicle search",
                        detail = "Make, year, mileage, price, power and gearbox, asked of 25 markets in their own languages",
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
                Spacer(Modifier.weight(1f))
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
