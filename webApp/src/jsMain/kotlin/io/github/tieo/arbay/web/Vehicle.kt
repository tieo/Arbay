package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.history.SearchHistoryStore
import io.github.tieo.arbay.history.summary
import io.github.tieo.arbay.model.BodyType
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.MarketSets
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VanSize
import io.github.tieo.arbay.results.VehicleSearch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Aside
import org.jetbrains.compose.web.dom.DataList
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Fieldset
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Header
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Legend
import org.jetbrains.compose.web.dom.Main
import org.jetbrains.compose.web.dom.Option
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.TextArea

/** A new vehicle search: the form in the middle, the vehicle searches run lately on the right. */
@Composable
fun VehicleScreen(app: WebApp) {
    val history by SearchHistoryStore.entries.collectAsState()
    Main({ classes("results", "form-page") }) {
        Header({ classes("results-head") }) { H1 { Text("Vehicle search") } }
        Div({ classes("form-scroll") }) {
            VehicleForm(VehicleSearch(), "Search") { search ->
                search.record()
                Router.go(Route.Results(Source.Vehicle(search.query)))
            }
        }
    }
    Aside({ classes("inspector") }) {
        Div({ classes("panel") }) {
            H2({ classes("panel-title") }) { Text("Lately") }
            history.filter { it.searchQuery.category == MarketGroup.VEHICLES }.forEach { entry ->
                RouteLink(Route.Results(Source.Vehicle(entry.searchQuery.text)), classes = listOf("recent")) {
                    Span({ classes("recent-name") }) { Text(entry.name) }
                    Span({ classes("muted", "small") }) { Text(entry.summary()) }
                }
            }
        }
    }
}

/** The criteria of the vehicle search that is open, edited beside its results. Applying them
 *  rewrites the search where it is kept, and the markets are asked again. */
@Composable
fun CriteriaPanel(app: WebApp, route: Route.Results, state: ResultsState) {
    val saved = state.open.saved ?: return
    Div({ classes("panel") }) {
        Div({ classes("panel-head") }) {
            H2 { Text("Criteria") }
            IconButton(Glyph.Close, "Close") { Router.replace(route.copy(panel = null)) }
        }
        VehicleForm(VehicleSearch.of(saved), "Apply") { search ->
            val bookmark = state.open.bookmark
            if (bookmark != null) app.products.updateProduct(bookmark.copy(name = search.name, searchQuery = search.applyTo(bookmark.searchQuery)))
            else search.record()
            Router.replace(Route.Results(if (bookmark != null) Source.Saved(bookmark.id) else Source.Vehicle(search.query)))
        }
    }
}

/** Every criterion a vehicle search can carry. The make is what the markets are asked for, so it
 *  is the one thing a search needs; everything else is optional. */
@Composable
private fun VehicleForm(initial: VehicleSearch, submitLabel: String, onSubmit: (VehicleSearch) -> Unit) {
    var makeText by remember(initial) { mutableStateOf(initial.make?.name.orEmpty()) }
    var modelText by remember(initial) { mutableStateOf(initial.model?.name.orEmpty()) }
    var filters by remember(initial) { mutableStateOf(initial.filters) }
    var platforms by remember(initial) { mutableStateOf(initial.platforms.toSet()) }
    var near by remember(initial) { mutableStateOf(initial.near.orEmpty()) }
    var radius by remember(initial) { mutableStateOf(initial.radiusKm?.toString().orEmpty()) }
    val make = VehicleSearch.makes.firstOrNull { it.name.equals(makeText.trim(), ignoreCase = true) }
    val model = make?.models?.firstOrNull { it.name.equals(modelText.trim(), ignoreCase = true) }

    Form(attrs = {
        classes("vehicle-form")
        addEventListener("submit") { event ->
            event.preventDefault()
            if (make != null && platforms.isNotEmpty()) onSubmit(VehicleSearch(make, model, filters, platforms.toList(), near, radius.toIntOrNull()))
        }
    }) {
        Fieldset {
            Legend { Text("Vehicle") }
            Div({ classes("field-row") }) {
                Field("Make") {
                    Input(InputType.Text) {
                        classes("control"); attr("list", "makes"); placeholder("Volkswagen, Ford, ...")
                        value(makeText); onInput { makeText = it.value; modelText = "" }
                    }
                    DataList({ id("makes") }) { VehicleSearch.makes.forEach { Option(it.name) } }
                }
                Field("Model") {
                    Input(InputType.Text) {
                        classes("control"); attr("list", "models"); placeholder(if (make == null) "Pick a make first" else "Any model")
                        if (make == null) attr("disabled", "")
                        value(modelText); onInput { modelText = it.value }
                    }
                    DataList({ id("models") }) { make?.models?.forEach { Option(it.name) } }
                }
            }
        }

        Fieldset {
            Legend { Text("What decides it") }
            Div({ classes("field-row") }) {
                NumberField("Registered from", filters.firstRegFromYear) { filters = filters.copy(firstRegFromYear = it) }
                NumberField("Registered until", filters.firstRegToYear) { filters = filters.copy(firstRegToYear = it) }
                NumberField("Mileage up to (km)", filters.maxMileageKm) { filters = filters.copy(maxMileageKm = it) }
            }
            Div({ classes("field-row") }) {
                NumberField("Price up to (€)", filters.maxPriceEur) { filters = filters.copy(maxPriceEur = it) }
                NumberField("Power from (kW)", filters.minPowerKw) { filters = filters.copy(minPowerKw = it) }
            }
            Div({ classes("chips") }) {
                Span({ classes("chips-label") }) { Text("Gearbox") }
                listOf(null to "Either", Transmission.MANUAL to "Manual", Transmission.AUTOMATIC to "Automatic").forEach { (value, label) ->
                    Chip(label, filters.transmission == value) { filters = filters.copy(transmission = value) }
                }
            }
            Div({ classes("chips") }) {
                Span({ classes("chips-label") }) { Text("Fuel") }
                listOf(Fuel.DIESEL, Fuel.PETROL, Fuel.ELECTRIC, Fuel.HYBRID_PETROL, Fuel.PLUGIN_HYBRID, Fuel.LPG).forEach { fuel ->
                    Chip(words(fuel.name), fuel in filters.fuels) {
                        filters = filters.copy(fuels = if (fuel in filters.fuels) filters.fuels - fuel else filters.fuels + fuel)
                    }
                }
            }
            Div({ classes("chips") }) {
                Span({ classes("chips-label") }) { Text("Body") }
                BodyType.entries.filter { it != BodyType.OTHER }.forEach { body ->
                    Chip(words(body.name), body in filters.bodyTypes) {
                        filters = filters.copy(bodyTypes = if (body in filters.bodyTypes) filters.bodyTypes - body else filters.bodyTypes + body)
                    }
                }
            }
        }

        Fieldset {
            Legend { Text("Van size") }
            Div({ classes("chips") }) {
                Span({ classes("chips-label") }) { Text("Length") }
                (1..4).forEach { size ->
                    Chip(VanSize.lengthLabel(size), size in filters.vanLengths) {
                        filters = filters.copy(vanLengths = if (size in filters.vanLengths) filters.vanLengths - size else filters.vanLengths + size)
                    }
                }
            }
            Div({ classes("chips") }) {
                Span({ classes("chips-label") }) { Text("Roof") }
                (1..3).forEach { size ->
                    Chip(VanSize.roofLabel(size), size in filters.vanHeights) {
                        filters = filters.copy(vanHeights = if (size in filters.vanHeights) filters.vanHeights - size else filters.vanHeights + size)
                    }
                }
            }
        }

        Fieldset {
            Legend { Text("Where") }
            Div({ classes("field-row") }) {
                Field("Near") {
                    Input(InputType.Text) { classes("control"); placeholder("Town or postcode"); value(near); onInput { near = it.value } }
                }
                Field("Within (km)") {
                    Input(InputType.Number) { classes("control"); placeholder("Anywhere"); value(radius); onInput { radius = it.value?.toString().orEmpty() } }
                }
            }
            Div({ classes("chips") }) {
                Span({ classes("chips-label") }) { Text("Markets") }
                MarketSets.vehicles.forEach { platform ->
                    Chip("${platform.displayName} · ${MarketSets.countryOf(platform)}", platform in platforms) {
                        platforms = if (platform in platforms) platforms - platform else platforms + platform
                    }
                }
            }
        }

        Fieldset {
            Legend { Text("The one you want") }
            TextArea(filters.idealDescription.orEmpty()) {
                classes("control", "area")
                placeholder("Described in your own words; offers that fit it best come first")
                attr("aria-label", "The vehicle you want, in your own words")
                onInput { filters = filters.copy(idealDescription = it.value.takeIf { v -> v.isNotBlank() }) }
            }
        }

        Div({ classes("form-actions") }) {
            PrimaryButton(submitLabel, Glyph.Search, enabled = make != null && platforms.isNotEmpty()) {}
        }
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Label(attrs = { classes("field") }) {
        Span({ classes("field-label") }) { Text(label) }
        content()
    }
}

@Composable
private fun NumberField(label: String, value: Int?, onChange: (Int?) -> Unit) {
    Field(label) {
        Input(InputType.Number) {
            classes("control")
            placeholder("Any")
            value(value?.toString().orEmpty())
            onInput { onChange(it.value?.toInt()) }
        }
    }
}

/** An enum name as words: PLUGIN_HYBRID reads "Plugin hybrid". */
private fun words(name: String): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
