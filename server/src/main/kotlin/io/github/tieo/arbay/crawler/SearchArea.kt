package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.SearchQuery

/**
 * Where a search is centred and how far it reaches, in the forms the markets ask for.
 *
 * Every site takes a different one: AutoScout24 wants a postcode and a radius, mobile.de a pair of
 * coordinates and a radius, Kleinanzeigen its own location id, OTOMoto a city and a distance. The
 * search carries one thing — a place someone typed, or their position — and this turns it into
 * whichever of those the site being asked understands.
 */
data class SearchArea(
    val latitude: Double,
    val longitude: Double,
    val radiusKm: Int,
    val country: String,
    /** The postcode nearest the centre, for the sites whose field is a postcode. */
    val zip: String?,
    /** What was typed, for the sites that take a place name. */
    val placeName: String?,
)

/**
 * The area a search covers, or null when it covers everywhere.
 *
 * A typed place wins over the searcher's own position: someone who wrote "Berlin" is looking at
 * Berlin from wherever they happen to be sitting.
 */
fun SearchQuery.area(country: String = "DE"): SearchArea? {
    val radius = radiusKm.takeIf { it > 0 } ?: return null
    val typed = location?.trim()?.takeIf { it.isNotBlank() }
    val coords = when {
        typed != null -> Geocoder.resolve(country, typed.takeIf { it.any(Char::isDigit) }, typed)
        userLat != null && userLon != null -> userLat!! to userLon!!
        else -> null
    } ?: return null
    return SearchArea(
        latitude = coords.first,
        longitude = coords.second,
        radiusKm = radius,
        country = country,
        zip = typed?.takeIf { it.all { c -> c.isDigit() } }
            ?: Geocoder.nearestZip(country, coords.first, coords.second),
        placeName = typed?.takeIf { it.any { c -> c.isLetter() } },
    )
}
