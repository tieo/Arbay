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
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.READABLE_WIDTH

/**
 * One group of listings that are not on the results screen, and the one reason they are not.
 *
 * [undo] is what puts them back where that is a single action — dropping a blocked word, widening
 * the band, unticking a market. A group with none is one the search itself decided.
 */
data class HiddenGroup(
    val label: String,
    val why: String,
    val listings: List<Listing>,
    val undoLabel: String? = null,
    val undo: (() -> Unit)? = null,
    /** What puts one of them back, when that is a thing that can be done to one at a time. */
    val restoreLabel: String? = null,
    val restore: ((Listing) -> Unit)? = null,
)

/**
 * Everything missing from the results, in one place, by reason.
 *
 * There were four separate ways to make a listing disappear and four separate places to read about
 * it: a hidden-count line, a removed-by-the-search line, a market picker, and a trash button whose
 * listings went nowhere anyone could look. Which one had taken a listing, and why, was not
 * answerable — so a filter that was wrong stayed wrong, because being wrong looked exactly like
 * the market having nothing.
 */
@Composable
fun HiddenSheet(
    groups: List<HiddenGroup>,
    searchQuery: String,
    onDismiss: () -> Unit,
) {
    val shown = remember(groups) { groups.filter { it.listings.isNotEmpty() } }
    var open by remember(shown) { mutableStateOf(shown.firstOrNull()?.label) }
    val group = shown.firstOrNull { it.label == open }
    val total = remember(shown) { shown.sumOf { it.listings.size } }

    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Not shown",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    "$total of what the markets sent for \"$searchQuery\", by what took them",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            shown.forEach { candidate ->
                FilterChip(
                    selected = candidate.label == open,
                    onClick = { open = candidate.label },
                    label = {
                        Text(
                            "${candidate.listings.size} ${candidate.label}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        group?.let { g ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    g.why,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (g.undo != null && g.undoLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = g.undo) {
                        Text(g.undoLabel, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.widthIn(max = READABLE_WIDTH).fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                items(g.listings, key = { it.id }) { listing ->
                    // One at a time, on the card it is about: putting every hidden listing back at
                    // once was the only thing this used to offer, and the link that did it for one
                    // sat in a band of empty sheet below the card's own divider, reading as
                    // belonging to the next listing.
                    ListingCard(
                        listing = listing,
                        searchQuery = searchQuery,
                        onRestore = g.restore?.let { restore -> { restore(listing) } },
                        restoreLabel = g.restoreLabel ?: "Put back",
                    )
                }
            }
        }
    }
}
