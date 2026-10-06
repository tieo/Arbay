package io.github.tieo.arbay.web

import kotlinx.browser.window

// The browser's geolocation, which the Kotlin browser bindings do not cover.

/** The device's position, asking the browser for it (which asks the person the first time). */
fun readPosition(onCoords: (Double?, Double?) -> Unit) {
    val geolocation = window.navigator.asDynamic().geolocation
    if (geolocation == null) {
        onCoords(null, null)
        return
    }
    val options = js("({ maximumAge: 600000, timeout: 15000 })")
    geolocation.getCurrentPosition(
        { position: dynamic -> onCoords(position.coords.latitude as Double, position.coords.longitude as Double) },
        { _: dynamic -> onCoords(null, null) },
        options,
    )
}

/** Runs [block] only if the site already may read the position, so nothing asks on its own. */
fun whenGeolocationGranted(block: () -> Unit) {
    val permissions = window.navigator.asDynamic().permissions ?: return
    permissions.query(js("({ name: 'geolocation' })")).then { status: dynamic ->
        if (status.state == "granted") block()
    }
}
