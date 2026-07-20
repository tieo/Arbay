package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*

/**
 * Parses a marktplaats.nl / 2dehands.be vehicle detail page into a verified VehicleInfo. The page
 * embeds an `attr` object in its hydration state carrying the full spec set the search card omits
 * (power, gearbox, fuel, doors, colour, body type), keyed by the site's Dutch labels. These are the
 * seller's declared attributes, so the values are recorded as verified and may exclude on a filter.
 */
object MarktplaatsDetailParser {

    fun parse(html: String): VehicleInfo? {
        // Vermogen is quoted in PK (metric horsepower) here, e.g. "142"; 1 PK = 0.7355 kW.
        val powerKw = attr(html, "Vermogen")?.filter { it.isDigit() }?.toIntOrNull()
            ?.let { (it * 0.7355).toInt() }?.takeIf { it in 20..1000 }

        val info = VehicleInfo(
            firstRegYear = attr(html, "Bouwjaar")?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1900..2100 },
            mileageKm = attr(html, "Kilometerstand")?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
            powerKw = powerKw,
            fuel = Fuel.parse(attr(html, "fuel") ?: attr(html, "Brandstof")),
            gearbox = when {
                attr(html, "Transmissie")?.contains("Auto", ignoreCase = true) == true -> Transmission.AUTOMATIC
                attr(html, "Transmissie")?.contains("Handgeschakeld", ignoreCase = true) == true -> Transmission.MANUAL
                else -> null
            },
            bodyType = BodyType.parse(attr(html, "Carrosserievorm") ?: attr(html, "Carrosserie")),
            doors = attr(html, "Aantal deuren")?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..7 },
            seats = attr(html, "Aantal zitplaatsen")?.filter { it.isDigit() }?.toIntOrNull()?.takeIf { it in 1..20 },
            color = attr(html, "color") ?: attr(html, "Kleur"),
        )

        return if (info == VehicleInfo()) null else VehicleTextParser.verifiedByPresence(info)
    }

    /** Read a `"<label>":"<value>"` pair out of the embedded attribute object. */
    private fun attr(html: String, label: String): String? =
        Regex("\"${Regex.escape(label)}\"\\s*:\\s*\"([^\"]{1,80})\"")
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
}
