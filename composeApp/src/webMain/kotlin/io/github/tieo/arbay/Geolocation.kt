package io.github.tieo.arbay

import kotlin.js.JsAny

// The browser's geolocation, which the Kotlin browser bindings do not cover.

private external interface GeoCoordinates : JsAny {
    val latitude: Double
    val longitude: Double
}

private external interface GeoPosition : JsAny {
    val coords: GeoCoordinates
}

private external interface PermissionStatus : JsAny {
    val state: String
}

@JsFun("(onPosition, onError) => navigator.geolocation ? navigator.geolocation.getCurrentPosition(onPosition, onError, { maximumAge: 600000, timeout: 15000 }) : onError()")
private external fun getCurrentPosition(onPosition: (GeoPosition) -> Unit, onError: () -> Unit)

@JsFun("(onGranted) => navigator.permissions && navigator.permissions.query({ name: 'geolocation' }).then(s => { if (s.state === 'granted') onGranted() })")
private external fun onGeolocationGranted(onGranted: () -> Unit)

/** The device's position, asking the browser for it (which asks the person the first time). */
internal fun readPosition(onCoords: (Double?, Double?) -> Unit) =
    getCurrentPosition({ onCoords(it.coords.latitude, it.coords.longitude) }, { onCoords(null, null) })

/** Runs [block] only if the site already may read the position. */
internal fun whenGeolocationGranted(block: () -> Unit) = onGeolocationGranted(block)
