package io.github.tieo.arbay.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.w3c.dom.url.URLSearchParams

/** Where a search on screen comes from: a saved search, words typed in, or the vehicle form. */
sealed interface Source {
    data class Saved(val id: String) : Source
    data class Typed(val text: String) : Source
    data class Vehicle(val text: String) : Source
}

/** What the right-hand pane shows beside a search's results, when it is not a listing. */
enum class Panel(val slug: String) {
    PRICES("prices"), HIDDEN("hidden"), MARKETS("markets"), WORDS("words"), ALERTS("alerts"), CRITERIA("criteria");

    companion object {
        fun of(slug: String?): Panel? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * Where the reader is in the app, as an address of its own: every state worth coming back to has a
 * URL, so the browser's back and forward, a bookmark and "open in new tab" work as on any web page.
 */
sealed interface Route {
    data object Home : Route
    data class Results(val source: Source, val listing: String? = null, val panel: Panel? = null) : Route
    data object VehicleForm : Route
    data object FreeItems : Route
    data object Settings : Route

    fun path(): String = when (this) {
        Home -> "/"
        is Results -> {
            val base = when (source) {
                is Source.Saved -> "/saved/${enc(source.id)}"
                is Source.Typed -> "/search/${enc(source.text)}"
                is Source.Vehicle -> "/vehicle/${enc(source.text)}"
            }
            base + (listing?.let { "/${enc(it)}" } ?: "") + (panel?.let { "?panel=${it.slug}" } ?: "")
        }
        VehicleForm -> "/vehicle"
        FreeItems -> "/free"
        Settings -> "/settings"
    }

    companion object {
        fun parse(path: String, query: String = ""): Route {
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }.map { dec(it) }
            val panel = Panel.of(URLSearchParams(query).get("panel"))
            fun results(source: Source) = Results(source, parts.getOrNull(2), panel)
            return when (parts.firstOrNull()) {
                null -> Home
                "saved" -> parts.getOrNull(1)?.let { results(Source.Saved(it)) } ?: Home
                "search" -> parts.getOrNull(1)?.let { results(Source.Typed(it)) } ?: Home
                "vehicle" -> parts.getOrNull(1)?.let { results(Source.Vehicle(it)) } ?: VehicleForm
                "free" -> FreeItems
                "settings" -> Settings
                else -> Home
            }
        }
    }
}

private fun enc(value: String): String = js("encodeURIComponent")(value) as String
private fun dec(value: String): String = js("decodeURIComponent")(value) as String

/** The route on screen, kept in step with the address bar both ways. */
object Router {
    var route: Route by mutableStateOf(Route.parse(window.location.pathname, window.location.search))
        private set

    init {
        window.addEventListener("popstate", {
            route = Route.parse(window.location.pathname, window.location.search)
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
