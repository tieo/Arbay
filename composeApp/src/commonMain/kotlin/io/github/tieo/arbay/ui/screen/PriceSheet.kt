package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.ui.AdaptiveSheet

/**
 * Whether a price is good.
 *
 * The summary, the distribution and the sold history used to sit above the first offer, which is
 * how one question came to have three competing answers before anything was for sale on screen.
 * They answer the same question, so they live in one place, reached from the price on the results.
 */
@Composable
fun PriceSheet(
    minPrice: Money?,
    medianPrice: Money?,
    maxPrice: Money?,
    minNewPrice: Money?,
    medianNewPrice: Money?,
    newCount: Int,
    minUsedPrice: Money?,
    medianUsedPrice: Money?,
    usedCount: Int,
    conditionFilter: String?,
    onConditionFilterChange: (String) -> Unit,
    newListings: List<Listing>,
    usedListings: List<Listing>,
    soldListings: List<Listing>,
    medianSoldPrice: Money?,
    soldLoading: Boolean,
    onSearchSold: () -> Unit,
    // Only the five eBay markets publish what sold; everywhere else this is thin or empty, and the
    // view says so rather than looking broken.
    soldPossible: Boolean,
    onBan: ((Listing) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Price",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PriceOverview(
                minPrice = minPrice,
                medianPrice = medianPrice,
                minNewPrice = minNewPrice,
                medianNewPrice = medianNewPrice,
                newCount = newCount,
                minUsedPrice = minUsedPrice,
                medianUsedPrice = medianUsedPrice,
                usedCount = usedCount,
                maxPrice = maxPrice,
                conditionFilter = conditionFilter,
                onConditionFilterChange = onConditionFilterChange,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            if (newListings.size + usedListings.size >= 3 || soldListings.isNotEmpty()) {
                PriceDistributionChart(
                    newListings = newListings,
                    usedListings = usedListings,
                    soldListings = soldListings,
                    medianNewPrice = medianNewPrice?.amount,
                    medianUsedPrice = medianUsedPrice?.amount,
                    conditionFilter = conditionFilter,
                    onSearchSold = onSearchSold,
                    soldLoading = soldLoading,
                    onBan = onBan,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "What sold",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                when {
                    !soldPossible -> Text(
                        "None of the markets in this search publish what sold, so there is nothing " +
                            "to compare an asking price against. Only the eBay markets do.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    soldLoading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Looking for sold listings…", style = MaterialTheme.typography.bodySmall)
                    }
                    soldListings.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Nothing loaded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onSearchSold) { Text("Load what sold") }
                    }
                    else -> {
                        medianSoldPrice?.let {
                            Text(
                                "${soldListings.size} sold · median ${it.format()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            soldListings.take(40).forEach { listing ->
                SoldHistoryRow(
                    listing = listing,
                    onBan = onBan?.let { ban -> { ban(listing) } },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
