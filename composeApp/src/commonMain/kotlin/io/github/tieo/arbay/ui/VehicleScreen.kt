package io.github.tieo.arbay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import io.github.tieo.arbay.model.CarMakeNode
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VanSize
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.navigation.Source
import io.github.tieo.arbay.results.FORM_BODIES
import io.github.tieo.arbay.results.FORM_FUELS
import io.github.tieo.arbay.results.ResultsState
import io.github.tieo.arbay.results.VehicleSearch
import io.github.tieo.arbay.results.applyCriteria
import io.github.tieo.arbay.results.vehicleWord

/** A new vehicle search, as a screen of its own. */
@Composable
fun VehicleScreen(session: Session) {
    val nav = LocalNavigator.current
    Bare("Vehicle search", onBack = { nav.back() }) {
        VehicleForm(VehicleSearch(), "Search") { search ->
            search.record()
            nav.replace(Route.Results(Source.Vehicle(search.query)))
        }
    }
}

/** The criteria of the open vehicle search, changed beside its results. */
@Composable
fun CriteriaPanel(session: Session, state: ResultsState) {
    val nav = LocalNavigator.current
    val saved = state.open.saved ?: return
    Text("Criteria", style = MaterialTheme.typography.titleLarge)
    VehicleFields(VehicleSearch.of(saved), "Apply", scrolls = false) { search ->
        nav.back()
        nav.replace(Route.Results(state.open.applyCriteria(session.products, search)))
    }
}

@Composable
private fun VehicleForm(initial: VehicleSearch, submit: String, onSubmit: (VehicleSearch) -> Unit) {
    Box(Modifier.fillMaxSize()) { VehicleFields(initial, submit, scrolls = true, onSubmit) }
}

/**
 * Every criterion a vehicle search can carry. The make is what the markets are asked for, so it is
 * the one a search needs; everything else is optional, and the button says so by waking only once a
 * make is picked.
 */
@Composable
private fun VehicleFields(initial: VehicleSearch, submit: String, scrolls: Boolean, onSubmit: (VehicleSearch) -> Unit) {
    var makeText by remember(initial) { mutableStateOf(initial.make?.name.orEmpty()) }
    var modelText by remember(initial) { mutableStateOf(initial.model?.name.orEmpty()) }
    var filters by remember(initial) { mutableStateOf(initial.filters) }
    var platforms by remember(initial) { mutableStateOf(initial.platforms.toSet()) }
    var near by remember(initial) { mutableStateOf(initial.near.orEmpty()) }
    var radius by remember(initial) { mutableStateOf(initial.radiusKm?.toString().orEmpty()) }
    val make = VehicleSearch.makes.firstOrNull { it.name.equals(makeText.trim(), true) }
    val model = make?.models?.firstOrNull { it.name.equals(modelText.trim(), true) }
    val ready = make != null && platforms.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f, fill = scrolls).let { if (scrolls) it.verticalScroll(rememberScrollState()) else it }
                .padding(horizontal = if (scrolls) arbay.pad else 0.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(arbay.gap),
        ) {
            Picker("Make", makeText, VehicleSearch.makes.map(CarMakeNode::name), "Volkswagen, Ford, ...") { makeText = it; modelText = "" }
            Picker("Model", modelText, make?.models?.map { it.name }.orEmpty(), if (make == null) "Pick a make first" else "Any model", enabled = make != null) { modelText = it }

            Group("What decides it")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Number("From year", filters.firstRegFromYear, Modifier.weight(1f)) { filters = filters.copy(firstRegFromYear = it) }
                Number("Until year", filters.firstRegToYear, Modifier.weight(1f)) { filters = filters.copy(firstRegToYear = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Number("Mileage up to km", filters.maxMileageKm, Modifier.weight(1f)) { filters = filters.copy(maxMileageKm = it) }
                Number("Price up to €", filters.maxPriceEur, Modifier.weight(1f)) { filters = filters.copy(maxPriceEur = it) }
            }
            Number("Power from kW", filters.minPowerKw, Modifier.fillMaxWidth()) { filters = filters.copy(minPowerKw = it) }
            Labelled("Gearbox") {
                listOf(null to "Either", Transmission.MANUAL to "Manual", Transmission.AUTOMATIC to "Automatic").forEach { (v, l) ->
                    Choice(l, filters.transmission == v) { filters = filters.copy(transmission = v) }
                }
            }
            Labelled("Fuel") {
                FORM_FUELS.forEach { f -> Choice(vehicleWord(f.name), f in filters.fuels) { filters = filters.copy(fuels = if (f in filters.fuels) filters.fuels - f else filters.fuels + f) } }
            }
            Labelled("Body") {
                FORM_BODIES.forEach { b -> Choice(vehicleWord(b.name), b in filters.bodyTypes) { filters = filters.copy(bodyTypes = if (b in filters.bodyTypes) filters.bodyTypes - b else filters.bodyTypes + b) } }
            }

            Group("Van size")
            Labelled("Length") {
                VanSize.lengths.forEach { n -> Choice(VanSize.lengthLabel(n), n in filters.vanLengths) { filters = filters.copy(vanLengths = if (n in filters.vanLengths) filters.vanLengths - n else filters.vanLengths + n) } }
            }
            Labelled("Roof") {
                VanSize.roofs.forEach { n -> Choice(VanSize.roofLabel(n), n in filters.vanHeights) { filters = filters.copy(vanHeights = if (n in filters.vanHeights) filters.vanHeights - n else filters.vanHeights + n) } }
            }

            Group("Where")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(near, { near = it }, Modifier.weight(2f), label = { Text("Near") }, placeholder = { Text("Town or postcode") }, singleLine = true)
                OutlinedTextField(radius, { radius = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Within km") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Labelled("Markets") {
                MarketSets.vehicles.forEach { p ->
                    Choice("${p.displayName} · ${MarketSets.countryOf(p)}", p in platforms) { platforms = if (p in platforms) platforms - p else platforms + p }
                }
            }

            Group("The one you want")
            OutlinedTextField(
                filters.idealDescription.orEmpty(),
                { filters = filters.copy(idealDescription = it.takeIf { v -> v.isNotBlank() }) },
                Modifier.fillMaxWidth().heightIn(min = 96.dp),
                placeholder = { Text("In your own words; the offers that fit it best come first") },
            )
        }
        Surface(color = if (scrolls) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surface) {
            Column {
                if (scrolls) HorizontalDivider(color = arbay.line)
                Button(
                    onClick = { if (ready) onSubmit(VehicleSearch(make, model, filters, platforms.toList(), near, radius.toIntOrNull())) },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = if (scrolls) arbay.pad else 0.dp, vertical = 10.dp).let { if (scrolls) it.navigationBarsPadding() else it },
                ) { Icon(Icons.Outlined.Search, null); HGap(); Text(submit) }
            }
        }
    }
}

@Composable
private fun Group(title: String) = SectionTitle(title, Modifier.padding(top = 12.dp))

@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Muted(label)
        Choices { content() }
    }
}

@Composable
private fun Number(label: String, value: Int?, modifier: Modifier, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value?.toString().orEmpty(), { onChange(it.filter(Char::isDigit).take(7).toIntOrNull()) }, modifier,
        label = { Text(label) }, placeholder = { Text("Any") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** A field that offers the names that match what is typed, closest first. */
@Composable
private fun Picker(label: String, value: String, options: List<String>, placeholder: String, enabled: Boolean = true, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val typed = value.trim()
    val matches = remember(typed, options) {
        if (typed.isEmpty()) options.take(8)
        else options.filter { it.contains(typed, true) }.sortedBy { if (it.startsWith(typed, true)) 0 else 1 }.take(8)
    }
    Box {
        OutlinedTextField(
            value, { onChange(it); open = true }, Modifier.fillMaxWidth(),
            label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true, enabled = enabled,
        )
        DropdownMenu(
            expanded = open && matches.isNotEmpty() && matches.none { it.equals(typed, true) },
            onDismissRequest = { open = false },
            properties = PopupProperties(focusable = false),
        ) {
            matches.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { onChange(name); open = false }) }
        }
    }
}
