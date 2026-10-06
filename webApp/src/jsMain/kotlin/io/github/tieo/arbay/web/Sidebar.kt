package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.tieo.arbay.results.savedSearchSubtitle
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
import org.w3c.dom.events.KeyboardEvent

/** The left pane: a search box, every saved search with what it found since it was last opened,
 *  and the ways to Free Items and Settings. */
@Composable
fun Sidebar(app: WebApp, route: Route) {
    val products by app.products.products.collectAsState()
    val status by app.products.status.collectAsState()
    LaunchedEffect(Unit) { app.products.loadProducts() }

    Nav({ classes("sidebar") }) {
        RouteLink(Route.Home, classes = listOf("brand")) { Text("Arbay") }
        SearchBox()

        H2({ classes("sidebar-heading") }) { Text("Saved searches") }
        Div({ classes("saved-list") }) {
            products.forEach { product ->
                val active = (route as? Route.Saved)?.id == product.id
                val found = status[product.id]
                RouteLink(
                    Route.Saved(product.id),
                    classes = listOfNotNull("saved", "active".takeIf { active }),
                ) {
                    Div({ classes("saved-text") }) {
                        Span({ classes("saved-name") }) { Text(product.name) }
                        Span({ classes("saved-meta") }) {
                            Text(savedSearchSubtitle(product) + (found?.let { " · " + watchLabel(it) } ?: ""))
                        }
                    }
                    found?.newSinceOpened?.takeIf { it > 0 }?.let { count ->
                        Span({ classes("badge") }) { Text("$count new") }
                    }
                }
            }
        }

        Div({ classes("sidebar-foot") }) {
            RouteLink(Route.FreeItems, classes = listOfNotNull("foot-link", "active".takeIf { route == Route.FreeItems })) {
                Text("Free Items")
            }
            RouteLink(Route.Settings, classes = listOfNotNull("foot-link", "active".takeIf { route == Route.Settings })) {
                Text("Settings")
            }
        }
    }
}

/** A search typed in: Enter runs it in the middle pane. "/" anywhere on the page puts the cursor
 *  here, as it does on most sites with a search. */
@Composable
private fun SearchBox() {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        document.addEventListener("keydown", { event ->
            val key = event as KeyboardEvent
            val typing = document.activeElement is HTMLInputElement
            if (key.key == "/" && !typing) {
                key.preventDefault()
                (document.getElementById("search") as? HTMLInputElement)?.focus()
            }
        })
    }
    Form(attrs = {
        classes("search")
        addEventListener("submit") { event ->
            event.preventDefault()
            text.trim().takeIf { it.isNotEmpty() }?.let { Router.go(Route.Search(it)) }
        }
    }) {
        Input(InputType.Search) {
            id("search")
            classes("search-input")
            placeholder("Search every market")
            value(text)
            onInput { text = it.value }
        }
    }
}
