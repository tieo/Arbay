package io.github.tieo.arbay.web

import io.github.tieo.arbay.navigation.Panel
import io.github.tieo.arbay.navigation.Route
import io.github.tieo.arbay.navigation.Source
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import io.github.tieo.arbay.DevicePosition
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.loadServerSettings
import io.github.tieo.arbay.positionFromHomeTown
import io.github.tieo.arbay.viewmodel.FreeItemViewModel
import io.github.tieo.arbay.viewmodel.ListingViewModel
import io.github.tieo.arbay.viewmodel.ProductViewModel
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.ContentBuilder
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLAnchorElement

fun main() {
    renderComposable(rootElementId = "root") { ArbayWeb() }
}

/** The page's state for the whole session: one client and one view model of each kind, as the
 *  phone app holds them. */
class WebApp {
    val client = ArbayClient()
    val products = ProductViewModel(client)
    val listings = ListingViewModel(client)
    val freeItems = FreeItemViewModel(client)
}

/**
 * Three panes across the window: what can be searched and what is saved on the left, the open
 * search's offers in the middle, and the offer being read, or a closer look at the search, on the
 * right. Nothing opens over anything else, so a listing is read while its list stays put.
 */
@Composable
fun ArbayWeb() {
    val app = remember { WebApp() }
    ApplyLook()
    LaunchedEffect(Unit) { loadServerSettings(app.client) }
    LaunchedEffect(Unit) { app.products.loadProducts() }
    LaunchedEffect(Unit) {
        // The browser's own position where the site may already read it, never asking from here;
        // the home town otherwise.
        whenGeolocationGranted { readPosition { lat, lon -> DevicePosition.set(lat, lon) } }
        positionFromHomeTown(app.client)
    }
    val route = Router.route
    Div({ classes("app") }) {
        Rail(app, route)
        when (route) {
            is Route.Results -> ResultsScreen(app, route)
            Route.Home -> HomeScreen(app)
            Route.VehicleForm -> VehicleScreen(app)
            Route.FreeItems -> FreeItemsScreen(app)
            Route.Settings -> SettingsScreen(app)
        }
    }
}

/** A link to a place in the app. A plain click moves there without loading the page again; a
 *  modified click, a middle click or "open in new tab" do what they do on any link. */
@Composable
fun RouteLink(
    to: Route,
    classes: List<String> = emptyList(),
    replace: Boolean = false,
    title: String? = null,
    content: ContentBuilder<HTMLAnchorElement>,
) {
    A(href = to.path(), attrs = {
        if (classes.isNotEmpty()) classes(*classes.toTypedArray())
        title?.let { attr("title", it) }
        onClick { event ->
            if (event.button.toInt() == 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
                event.preventDefault()
                if (replace) Router.replace(to) else Router.go(to)
            }
        }
    }, content = content)
}
