package io.github.tieo.arbay.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tieo.arbay.CarTaxonomyStore
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.CarMakeNode
import io.github.tieo.arbay.model.CarModelNode
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.ui.AdaptiveFormSheet
import kotlin.math.roundToInt

/** Car marketplaces, paired with the country whose stock they surface. Kept in the
 *  order a buyer scans: home market first, then the cross-border sourcing markets. */
private val CAR_MARKETS: List<Pair<PlatformId, String>> = listOf(
    PlatformId.AUTOSCOUT24 to "EU",
    PlatformId.MOBILE_DE to "DE",
    PlatformId.KLEINANZEIGEN to "DE",
    PlatformId.EBAY_DE to "DE",
    PlatformId.TRUCKSCOUT24 to "DE",
    PlatformId.OTOMOTO to "PL",
    PlatformId.SAUTO to "CZ",
    PlatformId.DBA to "DK",
    PlatformId.BILBASEN to "DK",
    PlatformId.BYTBIL to "SE",
    PlatformId.MARKTPLAATS to "NL",
)

/**
 * Structured entry form for a car search. Fields are ordered the way a buyer reasons:
 * vehicle, then age and mileage, then budget and power, then gearbox, then which markets
 * to scan. The result set is shown by the existing listings view, which receives the
 * built query, the selected markets and the filters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarSearchSheet(
    onDismiss: () -> Unit,
    onBack: (() -> Unit)? = null,
    onSearch: (name: String, query: String, platforms: List<PlatformId>, filters: CarFilters, make: CarMakeNode?, model: CarModelNode?) -> Unit,
    initialMake: CarMakeNode? = null,
    initialModel: CarModelNode? = null,
    initialFilters: CarFilters? = null,
) {
    val taxonomy = CarTaxonomyStore.taxonomy
    var make by remember { mutableStateOf(initialMake) }
    var model by remember { mutableStateOf(initialModel) }
    var showMakePicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    // Open the advanced section if any advanced filter is already set (editing an existing search).
    var showMore by remember { mutableStateOf(initialFilters?.let { it.maxMileageKm != null || it.maxPriceEur != null || it.minPowerKw != null || it.transmission != null } ?: false) }
    var yearFrom by remember { mutableStateOf(initialFilters?.firstRegFromYear?.toString() ?: "") }
    var yearTo by remember { mutableStateOf(initialFilters?.firstRegToYear?.toString() ?: "") }
    var maxKm by remember { mutableStateOf(initialFilters?.maxMileageKm?.toString() ?: "") }
    var maxPrice by remember { mutableStateOf(initialFilters?.maxPriceEur?.toString() ?: "") }
    var minPowerKw by remember { mutableStateOf(initialFilters?.minPowerKw?.toString() ?: "") }
    var transmission by remember { mutableStateOf(initialFilters?.transmission) }
    val selectedPlatforms = remember { mutableStateListOf<PlatformId>().apply { addAll(CAR_MARKETS.map { it.first }) } }

    fun buildFilters() = CarFilters(
        firstRegFromYear = yearFrom.toIntOrNull(),
        firstRegToYear = yearTo.toIntOrNull(),
        maxMileageKm = maxKm.filter { it.isDigit() }.toIntOrNull(),
        maxPriceEur = maxPrice.filter { it.isDigit() }.toIntOrNull(),
        minPowerKw = minPowerKw.toIntOrNull(),
        transmission = transmission,
    )

    AdaptiveFormSheet(onDismiss = onDismiss) {
        if (onBack != null) BackHandler(onBack = onBack)
        // Scrollable filters above, a sticky Search bar pinned at the bottom (like the
        // real car apps): every field is optional, so the bar is always reachable + enabled.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("Car search", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Spacer(Modifier.height(20.dp))

            // 1 — Vehicle
            SectionLabel("Vehicle")
            PickerField(
                label = "Make",
                value = make?.name,
                leadingIcon = Icons.Outlined.DirectionsCar,
                onClick = { showMakePicker = true },
            )
            Spacer(Modifier.height(10.dp))
            PickerField(
                label = "Model",
                value = model?.name,
                enabled = make != null,
                onClick = { showModelPicker = true },
            )

            Spacer(Modifier.height(16.dp))

            // More filters — collapsed by default so make/model + Show results fit on screen
            // without scrolling. Everything here is optional.
            Surface(
                onClick = { showMore = !showMore },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("More filters (year, price, power, markets)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Icon(if (showMore) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                }
            }

            AnimatedVisibility(visible = showMore) {
              Column {
            Spacer(Modifier.height(18.dp))

            // 2 — Age and mileage
            SectionLabel("Age & mileage")
            SliderNumberField("Year from", yearFrom, { yearFrom = it.take(4) }, 1995f, 2026f, 1)
            Spacer(Modifier.height(12.dp))
            SliderNumberField("Year to", yearTo, { yearTo = it.take(4) }, 1995f, 2026f, 1)
            Spacer(Modifier.height(12.dp))
            SliderNumberField("Max mileage (km)", maxKm, { maxKm = it }, 0f, 300000f, 5000)

            Spacer(Modifier.height(18.dp))

            // 3 — Budget and power
            SectionLabel("Budget & power")
            SliderNumberField("Max price (EUR)", maxPrice, { maxPrice = it }, 0f, 100000f, 1000)
            Spacer(Modifier.height(12.dp))
            SliderNumberField(
                "Min power (kW)", minPowerKw, { minPowerKw = it }, 0f, 300f, 5,
                supporting = minPowerKw.toIntOrNull()?.let { "≈ ${(it * 1.35962).toInt()} hp" },
            )

            Spacer(Modifier.height(18.dp))

            // 4 — Gearbox
            SectionLabel("Gearbox")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf<Pair<String, Transmission?>>(
                    "Any" to null, "Automatic" to Transmission.AUTOMATIC, "Manual" to Transmission.MANUAL,
                )
                options.forEachIndexed { i, (label, value) ->
                    SegmentedButton(
                        selected = transmission == value,
                        onClick = { transmission = value },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    ) { Text(label) }
                }
            }

            Spacer(Modifier.height(18.dp))

            // 5 — Markets
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Markets (${selectedPlatforms.size}/${CAR_MARKETS.size})", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { selectedPlatforms.clear(); selectedPlatforms.addAll(CAR_MARKETS.map { it.first }) }) { Text("All", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { selectedPlatforms.clear() }) { Text("None", style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CAR_MARKETS.forEach { (platform, country) ->
                    FilterChip(
                        selected = platform in selectedPlatforms,
                        onClick = {
                            if (platform in selectedPlatforms) selectedPlatforms.remove(platform)
                            else selectedPlatforms.add(platform)
                        },
                        label = { Text("${platform.displayName} · $country", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { if (platform in selectedPlatforms) Icon(Icons.Outlined.Check, null, modifier = Modifier.size(14.dp)) },
                    )
                }
            }
              }
            }

            Spacer(Modifier.height(8.dp))
        }

        // Sticky bottom Search bar — always visible + enabled; nothing is required, so a
        // filter-only search (e.g. year + price, no make) works too (filters apply source-side).
        Surface(
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            val query = listOfNotNull(make?.name, model?.name).joinToString(" ")
            val name = query.ifBlank { "Car search" }
            Button(
                onClick = { onSearch(name, query, selectedPlatforms.toList(), buildFilters(), make, model) },
                enabled = selectedPlatforms.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Show results")
            }
        }
    }

    if (showMakePicker) {
        SearchablePickerDialog(
            title = "Select make",
            options = taxonomy.makes,
            labelOf = { it.name },
            onDismiss = { showMakePicker = false },
            onSelect = { picked ->
                make = picked
                model = null
                showMakePicker = false
            },
        )
    }
    if (showModelPicker) {
        make?.let { mk ->
            SearchablePickerDialog(
                title = "Select ${mk.name} model",
                options = mk.models,
                labelOf = { it.name },
                onDismiss = { showModelPicker = false },
                onSelect = { picked ->
                    model = picked
                    showModelPicker = false
                },
            )
        }
    }
}

/** A read-only field styled like an input that opens a picker on tap. */
@Composable
private fun PickerField(
    label: String,
    value: String?,
    enabled: Boolean = true,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit,
) {
    Box {
        OutlinedTextField(
            value = value ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { Text("Select") },
            leadingIcon = leadingIcon?.let { { Icon(it, null) } },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent overlay captures the tap, since a read-only field swallows clicks.
        Box(
            Modifier.matchParentSize().clickable(enabled = enabled, onClick = onClick),
        )
    }
}

/** Common shorthand a buyer types for a make, mapped to a substring of the real name.
 *  Only the cases plain subsequence matching misses (an extra letter, a nickname). */
private val makeAliases = mapOf(
    "vw" to "volkswagen",
    "merc" to "mercedes",
    "benz" to "mercedes",
    "beemer" to "bmw",
    "bimmer" to "bmw",
    "chevy" to "chevrolet",
    "mini" to "mini",
    "range" to "land rover",
    "landy" to "land rover",
)

/** Rank of how well [label] matches [query]; null when it does not match at all.
 *  Lower is better. Substring beats alias beats in-order subsequence, so "golf" ranks
 *  Volkswagen models by the literal hit before scattered-letter ones. */
private fun fuzzyScore(label: String, query: String): Int? {
    val l = label.lowercase()
    val q = query.trim().lowercase()
    if (q.isEmpty()) return 0
    if (l.startsWith(q)) return 0
    if (l.contains(q)) return 1
    makeAliases[q]?.let { if (l.contains(it)) return 2 }
    // Subsequence: every query char appears in order (catches "vw" -> Volkswagen, "merc").
    var i = 0
    for (c in l) if (i < q.length && c == q[i]) i++
    return if (i == q.length) 3 else null
}

/** Single-select list with a type-to-filter box. The taxonomy is exhaustive, so there is
 *  no free-text "not listed" option. */
@Composable
private fun <T> SearchablePickerDialog(
    title: String,
    options: List<T>,
    labelOf: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    var queryText by remember { mutableStateOf("") }
    val filtered = remember(queryText, options) {
        if (queryText.isBlank()) options
        else options
            .mapNotNull { opt -> fuzzyScore(labelOf(opt), queryText)?.let { opt to it } }
            .sortedWith(compareBy({ it.second }, { labelOf(it.first) }))
            .map { it.first }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = queryText,
                    onValueChange = { queryText = it },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    items(filtered) { option ->
                        Text(
                            labelOf(option),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) }
                                .padding(vertical = 14.dp, horizontal = 4.dp),
                        )
                    }
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                "No matches",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 14.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/** A slider paired with an editable number box. The slider is a fast, coarse dragger; the
 *  text box stays authoritative and unbounded — a typed value beyond the slider range is
 *  kept, the slider just pins at its end. Empty means "no limit". */
@Composable
private fun SliderNumberField(
    label: String,
    value: String,
    onValue: (String) -> Unit,
    min: Float,
    max: Float,
    stepSize: Int,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    val current = value.filter { it.isDigit() }.toFloatOrNull()
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = value,
                onValueChange = { onValue(it.filter { c -> c.isDigit() }) },
                placeholder = { Text("any") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(128.dp),
            )
        }
        Slider(
            value = (current ?: min).coerceIn(min, max),
            // Continuous track (no tick dots — a 0..100000 range would render a noisy
            // dotted line), but the emitted value snaps to stepSize.
            onValueChange = { raw -> onValue(((raw / stepSize).roundToInt() * stepSize).toString()) },
            valueRange = min..max,
        )
        supporting?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
