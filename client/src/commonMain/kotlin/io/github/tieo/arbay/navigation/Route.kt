package io.github.tieo.arbay.navigation

import io.ktor.http.decodeURLPart
import io.ktor.http.encodeURLPathPart

/** Where a search on screen comes from: a saved search, words typed in, or the vehicle form. */
sealed interface Source {
    data class Saved(val id: String) : Source
    data class Typed(val text: String) : Source
    data class Vehicle(val text: String) : Source
}

/** A closer look at an open search, beside its results in a window or over them on a phone. */
enum class Panel(val slug: String) {
    PRICES("prices"), HIDDEN("hidden"), MARKETS("markets"), WORDS("words"), ALERTS("alerts"), CRITERIA("criteria");

    companion object {
        fun of(slug: String?): Panel? = entries.firstOrNull { it.slug == slug }
    }
}

/**
 * Every place in the app, the same in each app that draws it. In a browser each one is an address of
 * its own, so back, forward, bookmarks and new tabs work as on any web page; on a phone they are the
 * steps the back gesture walks.
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
                is Source.Saved -> "/saved/${source.id.encodeURLPathPart()}"
                is Source.Typed -> "/search/${source.text.encodeURLPathPart()}"
                is Source.Vehicle -> "/vehicle/${source.text.encodeURLPathPart()}"
            }
            base + (listing?.let { "/${it.encodeURLPathPart()}" } ?: "") + (panel?.let { "?panel=${it.slug}" } ?: "")
        }
        VehicleForm -> "/vehicle"
        FreeItems -> "/free"
        Settings -> "/settings"
    }

    companion object {
        /** The place an address names; [panel] is the address's `panel` parameter. */
        fun parse(path: String, panel: String? = null): Route {
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }.map { it.decodeURLPart() }
            fun results(source: Source) = Results(source, parts.getOrNull(2), Panel.of(panel))
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
