package io.github.tieo.arbay.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    onSearch: (name: String, query: String, platforms: List<PlatformId>, filters: CarFilters) -> Unit,
) {
    val taxonomy = CarTaxonomyStore.taxonomy
    var make by remember { mutableStateOf<CarMakeNode?>(null) }
    var model by remember { mutableStateOf<CarModelNode?>(null) }
    var showMakePicker by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var yearFrom by remember { mutableStateOf("") }
    var yearTo by remember { mutableStateOf("") }
    var maxKm by remember { mutableStateOf("") }
    var maxPrice by remember { mutableStateOf("") }
    var minPowerKw by remember { mutableStateOf("") }
    var transmission by remember { mutableStateOf<Transmission?>(null) }
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
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

            Spacer(Modifier.height(24.dp))

            // 6 — Actions
            val query = listOfNotNull(make?.name, model?.name).joinToString(" ")
            val name = query.ifBlank { "Car search" }
            Button(
                onClick = { onSearch(name, query, selectedPlatforms.toList(), buildFilters()) },
                enabled = make != null && selectedPlatforms.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Search")
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
        else options.filter { labelOf(it).contains(queryText, ignoreCase = true) }
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
