package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class Location(
    val city: String? = null,
    val zip: String? = null,
    val country: String? = null,
    val raw: String? = null,
    // Geocoded on the server from zip/city + country when the search carries the user's position, so
    // the listing's distance can be computed. Null when it could not be resolved.
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    companion object {
        fun parse(text: String): Location {
            val trimmed = text.trim()
            val zipMatch = Regex("""(\d{4,5})\s+(.+)""").find(trimmed)
            return if (zipMatch != null) {
                Location(
                    zip = zipMatch.groupValues[1],
                    city = zipMatch.groupValues[2],
                    raw = trimmed,
                )
            } else {
                Location(raw = trimmed)
            }
        }
    }
}
