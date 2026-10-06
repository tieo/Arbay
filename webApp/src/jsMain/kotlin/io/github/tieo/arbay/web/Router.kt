package io.github.tieo.arbay.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import io.github.tieo.arbay.navigation.Route
import org.w3c.dom.url.URLSearchParams

/** The route on screen, kept in step with the address bar both ways. */
object Router {
    private fun here() = Route.parse(window.location.pathname, URLSearchParams(window.location.search).get("panel"))

    var route: Route by mutableStateOf(here())
        private set

    init {
        window.addEventListener("popstate", {
            route = here()
        })
    }

    /** Goes somewhere new, as a step the back button returns from. */
    fun go(to: Route) {
        if (to == route) return
        window.history.pushState(null, "", to.path())
        route = to
    }

    /** Changes the current step in place: picking another listing of the same results is not a
     *  place to come back to one by one. */
    fun replace(to: Route) {
        if (to == route) return
        window.history.replaceState(null, "", to.path())
        route = to
    }
}
