package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.model.SuggestedTerm
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.READABLE_WIDTH

/**
 * The other words the markets themselves print under this search, and what the app makes of each.
 *
 * There is no percentage here. Five ways of scoring how likely a word is to mean the same thing
 * were measured against hand-labelled pairs and none of them separated a synonym from an
 * accessory, so what is shown is the reason a rule gave and the number of listings the word
 * actually turned out to add. Searching one costs a crawl per market, so it happens on a tap.
 */
@Composable
fun OtherWordsSheet(
    words: List<SuggestedTerm>,
    picked: List<String>,
    searchQuery: String,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Other words for it",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    "What the markets print under \"$searchQuery\". Each one searched costs a crawl.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }

        LazyColumn(
            modifier = Modifier.widthIn(max = READABLE_WIDTH).fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(words, key = { it.term }) { word ->
                val on = picked.any { it.equals(word.term, ignoreCase = true) }
                Surface(
                    onClick = { onToggle(word.term) },
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                word.term,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (word.worthTrying) FontWeight.SemiBold else FontWeight.Normal,
                                ),
                            )
                            Text(
                                word.why,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // What it turned out to be worth, once it has been searched. Before that
                        // there is nothing honest to put here.
                        word.added?.let { added ->
                            Text(
                                if (added > 0) "+$added" else "nothing new",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (added > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                        }
                        Switch(checked = on, onCheckedChange = { onToggle(word.term) })
                    }
                }
            }
        }
    }
}
