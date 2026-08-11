package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SortMode
import io.github.tieo.arbay.ui.AdaptiveSheet
import io.github.tieo.arbay.ui.READABLE_WIDTH
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.exp

/**
 * Everything that narrows a search, in one place.
 *
 * These controls used to sit on the results canvas, where they pushed the first offer off the
 * screen and left "filtering" meaning two different things: the market chips read what was already
 * fetched, the search form re-ran the crawl. Everything here reads what is already fetched. The
 * vehicle criteria, which cannot be answered from fetched results, say so and lead to the form.
 */
@Composable
fun FiltersSheet(
    // The band the price slider may cover, from the results themselves.
    priceMin: Float,
    priceMax: Float,
    priceRange: ClosedFloatingPointRange<Float>,
    onPriceRange: (ClosedFloatingPointRange<Float>) -> Unit,
    onPriceCommitted: () -> Unit,
    condition: String?,
    onCondition: (String?) -> Unit,
    newCount: Int,
    usedCount: Int,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    markets: List<MarketChoice>,
    // Empty means every market that answered, which is what "no filter" is.
    shownMarkets: Set<PlatformId>,
    onShowMarkets: (Set<PlatformId>) -> Unit,
    shownCountries: Set<String>,
    onShowCountries: (Set<String>) -> Unit,
    blockedTerms: List<String>,
    onUnblock: (String) -> Unit,
    onBlock: (String) -> Unit,
    activeCount: Int,
    onClearAll: () -> Unit,
    hasCarCriteria: Boolean,
    onEditCarCriteria: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Filters",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            if (activeCount > 0) {
                TextButton(onClick = onClearAll) { Text("Clear all") }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        }
        Column(
            modifier = Modifier.widthIn(max = READABLE_WIDTH).fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "These narrow what was already found. Nothing here searches again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (priceMax > priceMin) {
                FilterSection("Price") {
                    PriceBand(
                        priceMin = priceMin,
                        priceMax = priceMax,
                        range = priceRange,
                        onRange = onPriceRange,
                        onCommitted = onPriceCommitted,
                    )
                }
            }

            if (newCount > 0 || usedCount > 0) {
                FilterSection("Condition") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            null to "Any",
                            "NEW" to "New ($newCount)",
                            "USED" to "Used ($usedCount)",
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = condition == value,
                                onClick = { onCondition(value) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            }

            FilterSection("Order") {
                Column {
                    SortMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(selected = sort == mode, onClick = { onSort(mode) })
                            Spacer(Modifier.width(4.dp))
                            Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (markets.isNotEmpty()) {
                FilterSection("Markets and countries") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val narrowed = shownMarkets.isNotEmpty() || shownCountries.isNotEmpty()
                        Text(
                            "Tick as many as you want. A country takes every market in it. " +
                                "The list stays whole whatever is ticked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        MarketRow(
                            label = "Every market",
                            count = markets.sumOf { it.count },
                            selected = !narrowed,
                            onClick = {
                                onShowMarkets(emptySet())
                                onShowCountries(emptySet())
                            },
                        )
                        // Grouped by the country the listings are in, because "which markets" and
                        // "which countries" are one question asked at two grains: tapping the
                        // country takes all of it, tapping a market takes that one.
                        //
                        // Nearest first where the search knows where the searcher is: a list that
                        // starts at Austria because A comes first is a list ordered by nothing
                        // anyone cares about. Alphabetical is the fallback, not the rule.
                        val byCountry = markets.groupBy { it.country ?: "Home" }
                        val ordered = byCountry.entries.sortedWith(
                            compareBy<Map.Entry<String, List<MarketChoice>>> { entry ->
                                entry.value.mapNotNull { it.nearestKm }.minOrNull() ?: Double.MAX_VALUE
                            }.thenBy { it.key },
                        )
                        ordered.forEach { (country, unsorted) ->
                            val group = unsorted.sortedWith(
                                compareBy<MarketChoice> { it.nearestKm ?: Double.MAX_VALUE }
                                    .thenByDescending { it.count },
                            )
                            val pickedHere = group.count { it.platform in shownMarkets }
                            CountryRow(
                                country = country,
                                count = group.sumOf { it.count },
                                markets = group.size,
                                nearestKm = group.mapNotNull { it.nearestKm }.minOrNull(),
                                state = when {
                                    country in shownCountries || pickedHere == group.size -> ToggleableState.On
                                    pickedHere > 0 -> ToggleableState.Indeterminate
                                    else -> ToggleableState.Off
                                },
                                onClick = {
                                    // Ticking a country takes all of it and unticking gives all of
                                    // it back, so the markets under it follow the box above them.
                                    val taking = country !in shownCountries && pickedHere < group.size
                                    onShowCountries(
                                        if (taking) shownCountries + country else shownCountries - country,
                                    )
                                    onShowMarkets(
                                        if (taking) shownMarkets else shownMarkets - group.map { it.platform }.toSet(),
                                    )
                                },
                            )
                            group.forEach { market ->
                                // A market inside a ticked country is being shown, so it is ticked.
                                // Unticking it drops the country and keeps its siblings, which is
                                // the only reading of "not this one" that leaves the rest alone.
                                val wholeCountry = country in shownCountries
                                MarketRow(
                                    label = market.name,
                                    count = market.count,
                                    selected = wholeCountry || market.platform in shownMarkets,
                                    onClick = {
                                        when {
                                            wholeCountry -> {
                                                onShowCountries(shownCountries - country)
                                                onShowMarkets(
                                                    shownMarkets + group.map { it.platform } - market.platform,
                                                )
                                            }
                                            market.platform in shownMarkets ->
                                                onShowMarkets(shownMarkets - market.platform)
                                            else -> onShowMarkets(shownMarkets + market.platform)
                                        }
                                    },
                                    indented = true,
                                )
                            }
                        }
                    }
                }
            }

            FilterSection("Blocked words") {
                var typed by remember { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (blockedTerms.isEmpty()) {
                        Text(
                            "Nothing blocked. A word blocked here hides every offer whose title carries it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            blockedTerms.sorted().forEach { term ->
                                InputChip(
                                    selected = false,
                                    onClick = { onUnblock(term) },
                                    label = { Text(term, style = MaterialTheme.typography.labelSmall) },
                                    trailingIcon = {
                                        Icon(Icons.Default.Close, "Unblock", modifier = Modifier.size(14.dp))
                                    },
                                )
                            }
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text("Block a word") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = typed.isNotBlank(),
                            onClick = {
                                onBlock(typed.trim())
                                typed = ""
                            },
                        ) { Text("Block") }
                    }
                }
            }

            if (hasCarCriteria && onEditCarCriteria != null) {
                FilterSection("Vehicle criteria") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Year, mileage, power and the rest are asked of the markets themselves, " +
                                "so changing them runs the search again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onEditCarCriteria) {
                            Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Change and search again")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** A market as the filter list shows it: what it is called, where it is, and how many of the
 *  results on screen came from it. */
data class MarketChoice(
    val platform: PlatformId,
    val name: String,
    val country: String?,
    val count: Int,
    // How far the nearest offer from this market is, when the search knows where
    // the searcher is. Null when nothing here carries a place.
    val nearestKm: Double? = null,
)

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        content()
    }
}

/** A country, holding every market whose listings are in it. */
@Composable
private fun CountryRow(
    country: String,
    count: Int,
    markets: Int,
    nearestKm: Double? = null,
    state: ToggleableState,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (state != ToggleableState.Off) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 2.dp, end = 10.dp, top = 0.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Half-ticked when some of the country's markets are picked and the country itself
            // is not, so a country row never claims more than what is actually being shown.
            TriStateCheckbox(state = state, onClick = onClick)
            Text(
                country,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                buildString {
                    append(if (markets == 1) "1 market" else "$markets markets")
                    append(" · $count")
                    // How far away the nearest thing in this country is, which is why
                    // the country sits where it does in the list.
                    nearestKm?.let { append(" · from ${it.roundToInt()} km") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MarketRow(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    indented: Boolean = false,
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(start = if (indented) 12.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 2.dp, end = 10.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A box, because any number of these can be ticked at once. Colour alone said one
            // was chosen and never said a second could be.
            Checkbox(checked = selected, onCheckedChange = { onClick() })
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The price band, on a log scale: prices cluster low and trail high, so a linear track spends most
 * of its length on the few dear listings and leaves the cluster a sliver.
 */
@Composable
private fun PriceBand(
    priceMin: Float,
    priceMax: Float,
    range: ClosedFloatingPointRange<Float>,
    onRange: (ClosedFloatingPointRange<Float>) -> Unit,
    onCommitted: () -> Unit,
) {
    fun toLog(value: Float): Float {
        val lo = ln((priceMin + 1f).toDouble())
        val hi = ln((priceMax + 1f).toDouble())
        return (((ln((value + 1f).toDouble()) - lo) / (hi - lo)).toFloat()).coerceIn(0f, 1f)
    }

    fun fromLog(fraction: Float): Float {
        val lo = ln((priceMin + 1f).toDouble())
        val hi = ln((priceMax + 1f).toDouble())
        return (exp(lo + (hi - lo) * fraction.toDouble()) - 1.0).toFloat().coerceIn(priceMin, priceMax)
    }

    var minText by remember(range.start) { mutableStateOf(range.start.toInt().toString()) }
    var maxText by remember(range.endInclusive) { mutableStateOf(range.endInclusive.toInt().toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RangeSlider(
            value = toLog(range.start)..toLog(range.endInclusive),
            onValueChange = { onRange(fromLog(it.start)..fromLog(it.endInclusive)) },
            onValueChangeFinished = onCommitted,
            valueRange = 0f..1f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = minText,
                onValueChange = { text ->
                    minText = text.filter { it.isDigit() }
                    minText.toFloatOrNull()?.let {
                        onRange(it.coerceIn(priceMin, range.endInclusive)..range.endInclusive)
                        onCommitted()
                    }
                },
                label = { Text("From") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = maxText,
                onValueChange = { text ->
                    maxText = text.filter { it.isDigit() }
                    maxText.toFloatOrNull()?.let {
                        onRange(range.start..it.coerceIn(range.start, priceMax))
                        onCommitted()
                    }
                },
                label = { Text("To") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The conditions a filter can ask for, in the order the chips show them. */
internal fun conditionMatches(filter: String?, condition: Condition?): Boolean = when (filter) {
    "NEW" -> condition == Condition.NEW
    "USED" -> condition != null && condition != Condition.NEW
    else -> true
}
