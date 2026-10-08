package io.github.tieo.arbay.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.history.SearchHistoryStore
import io.github.tieo.arbay.history.summary
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.navigation.Source
import io.github.tieo.arbay.results.savedSearchSubtitle
import io.github.tieo.arbay.results.watchLabel

/**
 * Where the app opens: a search box, then the saved searches with what each found since it was last
 * opened, those with something new first, then the searches run lately that nobody saved.
 */
@Composable
fun HomeScreen(session: Session) {
    val nav = LocalNavigator.current
    val products by session.products.products.collectAsState()
    val status by session.products.status.collectAsState()
    val loading by session.products.loading.collectAsState()
    val error by session.products.error.collectAsState()
    val history by SearchHistoryStore.entries.collectAsState()
    val freeMatches by session.freeItems.newMatches.collectAsState()
    val ordered = products.sortedByDescending { status[it.id]?.newSinceOpened ?: 0 }
    val waiting = products.sumOf { status[it.id]?.newSinceOpened ?: 0 }
    val unsaved = history.filter { entry -> products.none { it.searchQuery.text.equals(entry.searchQuery.text, true) && it.searchQuery.category == entry.searchQuery.category } }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SearchBar(onSearch = { nav.go(Route.Results(Source.Typed(it))) }, onVehicle = { nav.go(Route.VehicleForm) }) }

        if (freeMatches.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().clickable { nav.top(Route.FreeItems) }.padding(horizontal = arbay.pad, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.CardGiftcard, null, tint = MaterialTheme.colorScheme.primary)
                Text("${freeMatches.size} free ${if (freeMatches.size == 1) "item" else "items"} near you since you last looked", style = MaterialTheme.typography.bodyMedium)
            }
        }

        item {
            Column(Modifier.padding(start = arbay.pad, end = arbay.pad, top = 20.dp, bottom = 6.dp)) {
                Text(
                    when {
                        products.isEmpty() -> "Saved searches"
                        waiting > 0 -> "$waiting new since you last looked"
                        else -> "Nothing new since you last looked"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                when {
                    error != null && products.isEmpty() -> Muted("The server did not answer: $error")
                    loading && products.isEmpty() -> Busy()
                    products.isEmpty() -> Muted("Save a search from its results to have it here, and watched if you like.")
                }
            }
        }
        items(ordered, key = { it.id }) { product ->
            val found = status[product.id]
            val fresh = found?.newSinceOpened ?: 0
            Row(
                Modifier.fillMaxWidth().clickable { nav.go(Route.Results(Source.Saved(product.id))) }.padding(horizontal = arbay.pad, vertical = arbay.gap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    if (product.searchQuery.category == MarketGroup.VEHICLES) Icons.Outlined.DirectionsCar else Icons.Outlined.BookmarkBorder,
                    null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(product.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(
                        listOfNotNull(savedSearchSubtitle(product), found?.takeIf { it.watched }?.let { watchLabel(it) }).joinToString(" · "),
                        maxLines = 1,
                    )
                }
                if (found?.watched == true) Icon(Icons.Outlined.Notifications, "Watched", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (fresh > 0) Count(fresh)
            }
        }

        if (unsaved.isNotEmpty()) {
            item { SectionTitle("Lately", Modifier.padding(start = arbay.pad, top = 24.dp, bottom = 4.dp)) }
            items(unsaved, key = { "h:" + it.searchQuery.category + it.searchQuery.text }) { entry ->
                val q = entry.searchQuery
                val vehicle = q.category == MarketGroup.VEHICLES
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { nav.go(Route.Results(if (vehicle) Source.Vehicle(q.text) else Source.Typed(q.text))) }
                        .padding(start = arbay.pad, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(if (vehicle) Icons.Outlined.DirectionsCar else Icons.Outlined.History, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(vertical = arbay.gap)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted(entry.summary(), maxLines = 1)
                    }
                    IconButton(onClick = { SearchHistoryStore.remove(q.text, q.category) }) {
                        Icon(Icons.Outlined.Close, "Forget", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Words typed here run as a search across every market; the car beside it opens the vehicle form. */
@Composable
private fun SearchBar(onSearch: (String) -> Unit, onVehicle: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = arbay.pad).padding(top = 12.dp)) {
        Text("Arbay", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp, start = 2.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search every market") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { IconButton(onClick = onVehicle) { Icon(Icons.Outlined.DirectionsCar, "Vehicle search") } },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { text.trim().takeIf { it.isNotEmpty() }?.let(onSearch) }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = arbay.line,
            ),
        )
    }
    HorizontalDivider(Modifier.padding(top = 16.dp), color = arbay.line)
}
