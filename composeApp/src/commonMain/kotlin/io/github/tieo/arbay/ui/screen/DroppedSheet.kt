package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
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
import io.github.tieo.arbay.model.DropReason
import io.github.tieo.arbay.model.DroppedListing
import io.github.tieo.arbay.model.label
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.READABLE_WIDTH

/**
 * What the markets sent that the search removed before showing anything.
 *
 * The reader's own filters have always been visible as a count they can widen. The search's own
 * removals were not: a market's whole answer could be discarded after its cards had already
 * appeared, leaving a shorter list and no way to tell a market that had nothing from a filter that
 * was wrong. Each listing here says which rule took it, and opens like any other, so a rule that is
 * wrong about a listing can be seen being wrong.
 */
@Composable
fun DroppedSheet(
    dropped: List<DroppedListing>,
    searchQuery: String,
    onDismiss: () -> Unit,
) {
    // The reasons in the order they were hit, most-hit first: a filter misfiring shows up as the
    // reason with the large number next to it.
    val byReason: List<Pair<DropReason, List<DroppedListing>>> = remember(dropped) {
        dropped.groupBy { it.reason }.entries
            .sortedByDescending { it.value.size }
            .map { it.key to it.value }
    }
    var openReason by remember(dropped) { mutableStateOf(byReason.firstOrNull()?.first) }
    val shown = byReason.firstOrNull { it.first == openReason }?.second ?: emptyList()

    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Removed by the search",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    "${dropped.size} of what the markets sent for \"$searchQuery\"",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }

        // Wrapped, not a single row: eight reasons do not fit across a phone, and the ones that
        // fall off the end are exactly the rules nobody would think to check.
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            byReason.forEach { (reason, group) ->
                FilterChip(
                    selected = reason == openReason,
                    onClick = { openReason = reason },
                    label = {
                        Text(
                            "${group.size} ${reason.label}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                )
            }
        }

        Text(
            explain(openReason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        LazyColumn(
            modifier = Modifier.widthIn(max = READABLE_WIDTH).fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            items(shown, key = { it.listing.id }) { entry ->
                ListingCard(
                    listing = entry.listing,
                    searchQuery = searchQuery,
                    modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
    }
}

/** What the rule behind a reason is actually doing, in the words of the thing it is looking at. */
private fun explain(reason: DropReason?): String = when (reason) {
    DropReason.MARKET_IGNORED_SEARCH ->
        "Not one listing this market sent carries a word of the search, so it answered a " +
            "different question and its whole answer was set aside."
    DropReason.OFF_TARGET ->
        "The title carries too few of the words searched for."
    DropReason.BUILT_INTO_A_DEVICE ->
        "The title names a machine of its own and lists the thing searched for among its parts."
    DropReason.ACCESSORY ->
        "The title reads as a part or add-on made for the thing rather than the thing itself."
    DropReason.CONSUMABLE ->
        "The title reads as something the thing uses up — paper, bags, filters."
    DropReason.WANTED_AD ->
        "The title reads as someone asking to buy one, or as a job ad."
    DropReason.RENTAL ->
        "The title offers it for hire, so its price is a rate rather than what one costs."
    DropReason.NOT_A_SINGLE_OFFER ->
        "A placeholder title, or a bulk lot priced for many units."
    DropReason.IMPLAUSIBLE_PRICE ->
        "The price read off the page is not a price — digits from several fields run together."
    null -> ""
}
