package io.github.tieo.arbay.model

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance between two coordinates. Shared so the client can measure a listing's
 *  distance from the device without asking the server to crawl again: the server geocodes every
 *  listing's location into coordinates, the client only needs the arithmetic. */
object GeoDistance {
    private const val EARTH_RADIUS_KM = 6371.0

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }

    /** Distance from the given position to the listing, or null when the listing has no
     *  resolved coordinates. */
    fun to(listing: Listing, lat: Double, lon: Double): Double? {
        val la = listing.location?.latitude ?: return null
        val lo = listing.location?.longitude ?: return null
        return haversine(lat, lon, la, lo)
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
}
