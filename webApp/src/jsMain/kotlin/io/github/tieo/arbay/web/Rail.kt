package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.results.watchLabel
import kotlinx.browser.document
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.placeholder
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Form
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Nav
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent

/** The left rail: a search box, the vehicle search, every saved search with what it found since
 *  it was last opened, Free Items and Settings. */
@Composable
fun Rail(app: WebApp, route: Route) {
    val products by app.products.products.collectAsState()
    val status by app.products.status.collectAsState()
    val freeMatches by app.freeItems.newMatches.collectAsState()
    val openSaved = ((route as? Route.Results)?.source as? Source.Saved)?.id

    Nav({ classes("rail") }) {
        RouteLink(Route.Home, classes = listOf("brand")) {
            Span({ classes("brand-mark") }) {}
            Text("Arbay")
        }
        SearchBox(route)
        RouteLink(Route.VehicleForm, classes = listOfNotNull("rail-link", "active".takeIf { route == Route.VehicleForm || (route as? Route.Results)?.source is Source.Vehicle })) {
            Icon(Glyph.Car)
            Text("Vehicle search")
        }

        H2({ classes("rail-heading") }) { Text("Saved") }
        Div({ classes("saved-list") }) {
            products.forEach { product ->
                val found = status[product.id]
                RouteLink(
                    Route.Results(Source.Saved(product.id)),
                    classes = listOfNotNull("saved", "active".takeIf { openSaved == product.id }),
                    title = found?.let { watchLabel(it) },
                ) {
                    Icon(if (product.searchQuery.category == MarketGroup.VEHICLES) Glyph.Car else Glyph.Bookmark, 16)
                    Span({ classes("saved-name") }) { Text(product.name) }
                    if (found?.watched == true) Span({ classes("watching"); attr("title", watchLabel(found)) }) { Icon(Glyph.Bell, 14) }
                    found?.newSinceOpened?.takeIf { it > 0 }?.let { count ->
                        Span({ classes("count") }) { Text("$count") }
                    }
                }
            }
        }

        Div({ classes("rail-foot") }) {
            RouteLink(Route.FreeItems, classes = listOfNotNull("rail-link", "active".takeIf { route == Route.FreeItems })) {
                Icon(Glyph.Gift)
                Text("Free Items")
                freeMatches.size.takeIf { it > 0 }?.let { Span({ classes("count") }) { Text("$it") } }
            }
            RouteLink(Route.Settings, classes = listOfNotNull("rail-link", "active".takeIf { route == Route.Settings })) {
                Icon(Glyph.Settings)
                Text("Settings")
            }
        }
    }
}

/** Words typed here run as a search across every market. "/" anywhere puts the cursor here. */
@Composable
private fun SearchBox(route: Route) {
    val typed = ((route as? Route.Results)?.source as? Source.Typed)?.text.orEmpty()
    var text by remember(typed) { mutableStateOf(typed) }
    DisposableEffect(Unit) {
        val handler: (org.w3c.dom.events.Event) -> Unit = { event ->
            val key = event as KeyboardEvent
            val target = document.activeElement
            val typing = target is HTMLInputElement || target is HTMLTextAreaElement || target is HTMLSelectElement
            if (key.key == "/" && !typing) {
                key.preventDefault()
                (document.getElementById("search") as? HTMLInputElement)?.focus()
            }
        }
        document.addEventListener("keydown", handler)
        onDispose { document.removeEventListener("keydown", handler) }
    }
    Form(attrs = {
        classes("search")
        addEventListener("submit") { event ->
            event.preventDefault()
            text.trim().takeIf { it.isNotEmpty() }?.let { Router.go(Route.Results(Source.Typed(it))) }
        }
    }) {
        Icon(Glyph.Search)
        Input(InputType.Search) {
            id("search")
            placeholder("Search every market")
            attr("aria-label", "Search every market")
            value(text)
            onInput { text = it.value }
        }
        Span({ classes("kbd"); attr("aria-hidden", "true") }) { Text("/") }
    }
}
