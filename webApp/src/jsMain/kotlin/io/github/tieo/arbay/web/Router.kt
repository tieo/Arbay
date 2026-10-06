package io.github.tieo.arbay.web

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.w3c.dom.url.URLSearchParams

/**
 * Where the reader is in the app, as an address of its own: a saved search, a search typed in, one
 * listing inside either. Every state worth going back to has a URL, so the browser's back and
 * forward, a bookmark and "open in new tab" all work the way they do on any web page.
 */
sealed interface Route {
    /** The listing open beside the results, where there is one. */
    val listing: String? get() = null

    data object Home : Route
    data class Saved(val id: String, override val listing: String? = null) : Route
    data class Search(val text: String, override val listing: String? = null) : Route
    data object FreeItems : Route
    data object Settings : Route

    /** The same view with one listing open, or none. */
    fun withListing(listingId: String?): Route = when (this) {
        is Saved -> copy(listing = listingId)
        is Search -> copy(listing = listingId)
        else -> this
    }

    fun path(): String = when (this) {
        Home -> "/"
        is Saved -> "/saved/${enc(id)}" + (listing?.let { "/${enc(it)}" } ?: "")
        is Search -> "/search/${enc(text)}" + (listing?.let { "/${enc(it)}" } ?: "")
        FreeItems -> "/free"
        Settings -> "/settings"
    }

    companion object {
        fun parse(path: String, query: String = ""): Route {
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }.map { dec(it) }
            return when (parts.firstOrNull()) {
                null -> URLSearchParams(query).get("q")?.takeIf { it.isNotBlank() }?.let { Search(it) } ?: Home
                "saved" -> parts.getOrNull(1)?.let { Saved(it, parts.getOrNull(2)) } ?: Home
                "search" -> parts.getOrNull(1)?.let { Search(it, parts.getOrNull(2)) } ?: Home
                "free" -> FreeItems
                "settings" -> Settings
                else -> Home
            }
        }
    }
}

private fun enc(value: String): String = encodeURIComponent(value)
private fun dec(value: String): String = decodeURIComponent(value)

private external fun encodeURIComponent(value: String): String
private external fun decodeURIComponent(value: String): String

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

    /** Changes the current step in place: choosing another listing in the same results is not a
     *  place to come back to one by one. */
    fun replace(to: Route) {
        if (to == route) return
        window.history.replaceState(null, "", to.path())
        route = to
    }
}
