package io.github.tieo.arbay

import androidx.compose.runtime.Composable

/**
 * Returns a function that, when invoked, detects the device's current city
 * and calls [onCity] with the result (null if unavailable or permission denied).
 */
@androidx.compose.runtime.Composable
expect fun rememberCityDetector(onCity: (String?) -> Unit): () -> Unit

/** Returns a trigger that fetches the device's coordinates (for nearest-first car results) and calls
 *  back with lat/lon, or (null, null) if unavailable/denied. */
@androidx.compose.runtime.Composable
expect fun rememberCoordDetector(onCoords: (Double?, Double?) -> Unit): () -> Unit

/**
 * The device's position, but only where the app already has permission to read it, and never by
 * asking. Distance to a listing is worth showing on every card, and it was only ever worked out
 * after someone switched the order to nearest-first, so a list showed postcodes and nothing else.
 */
@Composable
expect fun ReadPositionIfAllowed(onCoords: (Double?, Double?) -> Unit)

/** What the image loader should be handed for a picture's address. A listing off a market carries
 *  an http address, which every platform loads as it stands; one drawn off-screen carries a path,
 *  which the JVM has to be given as a file rather than as an unresolvable URI. */
expect fun imageModel(address: String): Any

/** Schedule/reschedule background polling with given interval. No-op on non-Android. */
expect fun schedulePolling(intervalMinutes: Int)

/** Cancel background polling. No-op on non-Android. */
expect fun cancelPolling()
