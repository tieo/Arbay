package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
data class Location(
    val city: String? = null,
    val zip: String? = null,
    val country: String? = null,
    val raw: String? = null,
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
