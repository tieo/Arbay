package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SaleType
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
    // Which conditions are being looked at, and whether listings whose market never said are
    // among them. An empty set is every condition.
    conditions: Set<Condition>,
    onConditions: (Set<Condition>) -> Unit,
    unstatedCondition: Boolean,
    onUnstatedCondition: (Boolean) -> Unit,
    /** How many of the fetched listings are in each condition; null counts the ones with none. */
    conditionCounts: Map<Condition?, Int>,
    // How the listings being looked at are sold. An empty set is every way.
    saleTypes: Set<SaleType>,
    onSaleTypes: (Set<SaleType>) -> Unit,
    unstatedSaleType: Boolean,
    onUnstatedSaleType: (Boolean) -> Unit,
    /** How many of the fetched listings are sold each way; null counts the ones whose market never said. */
    saleTypeCounts: Map<SaleType?, Int>,
    sort: SortMode,
    onSort: (SortMode) -> Unit,
    markets: List<MarketChoice>,
    // Empty means every market that answered, which is what "no filter" is. The picking itself is
    // on the markets screen; this only says how things stand and leads there.
    shownMarkets: Set<PlatformId>,
    shownCountries: Set<String>,
    onOpenMarkets: (() -> Unit)? = null,
    // Where the search is centred and how far it reaches. Empty means everywhere.
    near: String? = null,
    radiusKm: Int? = null,
    onNear: ((String?, Int?) -> Unit)? = null,
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

            if (conditionCounts.values.sum() > 0) {
                FilterSection("Condition") {
                    // One chip per condition the results actually contain, each on or off by
                    // itself: "new and used", "for parts only", "refurbished and like new" are all
                    // sayable. Nothing picked means every condition, which is what the word on the
                    // chip row says rather than a chip called "Any" competing with the rest.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Condition.entries
                            .filter { (conditionCounts[it] ?: 0) > 0 }
                            .forEach { value ->
                                FilterChip(
                                    selected = value in conditions,
                                    onClick = {
                                        onConditions(
                                            if (value in conditions) conditions - value else conditions + value,
                                        )
                                    },
                                    label = { Text("${value.label} (${conditionCounts[value] ?: 0})") },
                                )
                            }
                        conditionCounts[null]?.takeIf { it > 0 }?.let { count ->
                            FilterChip(
                                selected = unstatedCondition,
                                onClick = { onUnstatedCondition(!unstatedCondition) },
                                label = { Text("Not stated ($count)") },
                            )
                        }
                    }
                }
            }

            // Offered only where the results hold both kinds, since a screen of shop listings has
            // no auction to take out and the chips would be a filter over one thing.
            if (saleTypeCounts.keys.filterNotNull().size > 1) {
                FilterSection("How it is sold") {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SaleType.entries
                            .filter { (saleTypeCounts[it] ?: 0) > 0 }
                            .forEach { value ->
                                FilterChip(
                                    selected = value in saleTypes,
                                    onClick = {
                                        onSaleTypes(
                                            if (value in saleTypes) saleTypes - value else saleTypes + value,
                                        )
                                    },
                                    label = { Text("${value.label} (${saleTypeCounts[value] ?: 0})") },
                                )
                            }
                        saleTypeCounts[null]?.takeIf { it > 0 }?.let { count ->
                            FilterChip(
                                selected = unstatedSaleType,
                                onClick = { onUnstatedSaleType(!unstatedSaleType) },
                                label = { Text("Not stated ($count)") },
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
                FilterSection("Markets") {
                    // The markets live on their own screen, where each one also says what it
                    // answered. Two lists of the same markets, one saying what happened and one
                    // where you chose, meant the choosing screen quietly showed fewer of them.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            when {
                                shownCountries.isNotEmpty() && shownMarkets.isNotEmpty() ->
                                    "Narrowed to ${shownMarkets.size} markets and ${shownCountries.size} countries."
                                shownMarkets.isNotEmpty() ->
                                    if (shownMarkets.size == 1) "Narrowed to one market."
                                    else "Narrowed to ${shownMarkets.size} markets."
                                shownCountries.isNotEmpty() ->
                                    if (shownCountries.size == 1) "Narrowed to one country."
                                    else "Narrowed to ${shownCountries.size} countries."
                                else -> "Every market that was asked is being shown."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { onOpenMarkets?.invoke() }) {
                            Icon(Icons.Outlined.Storefront, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Markets, and what each one said")
                        }
                    }
                }
            }

            if (onNear != null) {
                FilterSection("Where to look") {
                    var place by remember(near) { mutableStateOf(near.orEmpty()) }
                    var km by remember(radiusKm) { mutableStateOf(radiusKm?.takeIf { it > 0 }?.toString() ?: "") }
                    Text(
                        "The markets that take a place are asked near it — AutoScout24 by postcode, " +
                            "mobile.de by point. The rest answer their whole country and what they " +
                            "publish is measured against this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = place,
                            onValueChange = { place = it },
                            label = { Text("Near") },
                            placeholder = { Text("town or postcode") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(2f),
                        )
                        OutlinedTextField(
                            value = km,
                            onValueChange = { km = it.filter { c -> c.isDigit() }.take(4) },
                            label = { Text("km") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            onNear(place.trim().takeIf { it.isNotBlank() }, km.toIntOrNull()?.takeIf { it > 0 })
                        }) { Text("Apply") }
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
    // Why a market has nothing here: it answered with nothing, or it never answered. Null when it
    // has offers. A market that was asked belongs on this list whatever came back, or the list
    // reads as the whole of what was searched when it is only the part that succeeded.
    val emptyBecause: String? = null,
)

@Composable
private fun FilterSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        content()
    }
}

/** A country, holding every market whose listings are in it. */
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

/** What a condition is called on a chip and in a sentence. */
internal val Condition.label: String
    get() = when (this) {
        Condition.NEW -> "New"
        Condition.LIKE_NEW -> "Like new"
        Condition.VERY_GOOD -> "Very good"
        Condition.GOOD -> "Good"
        Condition.ACCEPTABLE -> "Acceptable"
        Condition.USED -> "Used"
        Condition.REFURBISHED -> "Refurbished"
        Condition.PARTS_ONLY -> "For parts"
    }

/**
 * Whether a listing is in one of the conditions being looked at.
 *
 * No condition picked is every condition. A listing whose market never said is its own answer
 * ("Not stated"), rather than being counted as used: a broken drive sold for parts and a working
 * one were both "not new", so a search could not be told to leave the broken ones out.
 */
/** Whether a listing sold this way belongs on a screen narrowed to [wanted]. */
internal fun saleTypeMatches(
    wanted: Set<SaleType>,
    unstated: Boolean,
    saleType: SaleType?,
): Boolean = when {
    wanted.isEmpty() -> saleType != null || unstated
    saleType == null -> unstated
    else -> saleType in wanted
}

internal fun conditionMatches(
    wanted: Set<Condition>,
    unstated: Boolean,
    condition: Condition?,
): Boolean = when {
    wanted.isEmpty() -> condition != null || unstated
    condition == null -> unstated
    else -> condition in wanted
}
