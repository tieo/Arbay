package io.github.tieo.arbay.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.tieo.arbay.DevicePosition
import io.github.tieo.arbay.api.ArbayClient
import io.github.tieo.arbay.loadServerSettings
import io.github.tieo.arbay.positionFromHomeTown
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

/** The app's state for the whole page: one client and one view model of each kind, as the phone
 *  app holds them for its whole session. */
class WebApp {
    val client = ArbayClient()
    val products = ProductViewModel(client)
    val listings = ListingViewModel(client)
}

/**
 * Three panes side by side: the saved searches down the left, the results of the one open in the
 * middle, and the listing chosen from them on the right. Nothing opens over anything else, so a
 * listing can be read while the list it came from stays where it was.
 */
@Composable
fun ArbayWeb() {
    val app = remember { WebApp() }
    LaunchedEffect(Unit) { loadServerSettings(app.client) }
    LaunchedEffect(Unit) {
        // The browser's own position where the site may already read it, never asking here; the
        // home town otherwise.
        whenGeolocationGranted {
            readPosition { lat, lon -> DevicePosition.set(lat, lon) }
        }
        positionFromHomeTown(app.client)
    }
    val route = Router.route
    Div({ classes("app") }) {
        Sidebar(app, route)
        when (route) {
            is Route.Saved, is Route.Search -> ResultsAndDetail(app, route)
            Route.Home -> Welcome(app)
            Route.FreeItems -> NotYet("Free Items")
            Route.Settings -> NotYet("Settings")
        }
    }
}

/** A link to a place in the app. A plain click moves there without loading the page again; a
 *  click with a modifier, a middle click or "open in new tab" do what they do on any link. */
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
