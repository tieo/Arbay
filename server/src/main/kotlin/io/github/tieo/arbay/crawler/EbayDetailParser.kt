package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.BodyType
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VehicleInfo
import org.jsoup.Jsoup

/**
 * Reads eBay's "Info zum Artikel" table, which carries exactly the specs a vehicle search filters
 * on: Kilometer, Jahr der Erstzulassung, Leistung, Getriebeart, Kraftstoffart, Schadstoffklasse,
 * Fahrzeugtyp, Farbe.
 *
 * The search card carries none of it, so before this every eBay listing reached the filters with
 * every spec unknown and was kept on the rule that an unknown spec is not a mismatch. A whole
 * market's listings arriving unfilterable is not a filtering policy, it is a missing parser.
 */
object EbayDetailParser {

    fun parse(html: String): VehicleInfo? {
        val doc = Jsoup.parse(html)
        val attrs = HashMap<String, String>()

        // The modern layout pairs a label div with a value div; the older one is a plain table.
        for (row in doc.select("div.ux-layout-section__row, div.ux-layout-section-evo__row")) {
            val cols = row.select("div.ux-labels-values__labels, div.ux-labels-values__values")
            if (cols.size >= 2) {
                val label = cols[0].text().trim().trimEnd(':')
                val value = cols[1].text().trim()
                if (label.isNotBlank() && value.isNotBlank()) attrs[label.lowercase()] = value
            }
        }
        for (row in doc.select("table tr")) {
            val cells = row.select("th, td")
            if (cells.size == 2) {
                val label = cells[0].text().trim().trimEnd(':')
                val value = cells[1].text().trim()
                if (label.isNotBlank() && value.isNotBlank()) attrs.putIfAbsent(label.lowercase(), value)
            }
        }
        if (attrs.isEmpty()) return null

        fun number(key: String): Int? =
            attrs[key]?.replace(Regex("""[^0-9]"""), "")?.toIntOrNull()

        val year = number("jahr der erstzulassung")?.takeIf { it in 1950..2035 }
            ?: number("erstzulassung")?.takeIf { it in 1950..2035 }
        val km = number("kilometer")?.takeIf { it in 1..2_000_000 }
            ?: number("kilometerstand")?.takeIf { it in 1..2_000_000 }
        // "Leistung 100" on a van is kW, the unit German registration papers use. A three-digit
        // value with "ps" beside it is horsepower and converts.
        val powerRaw = attrs["leistung"]
        val powerKw = powerRaw?.let { raw ->
            val n = raw.replace(Regex("""[^0-9]"""), "").toIntOrNull()
            when {
                n == null -> null
                raw.contains("ps", ignoreCase = true) -> (n * 0.7355).toInt()
                else -> n
            }?.takeIf { it in 20..1000 }
        }
        val gearbox = attrs["getriebeart"]?.let {
            when {
                it.contains("automat", ignoreCase = true) -> Transmission.AUTOMATIC
                it.contains("schalt", ignoreCase = true) -> Transmission.MANUAL
                else -> null
            }
        }
        val fuel = Fuel.parse(attrs["kraftstoffart"])
        val emission = attrs["schadstoffklasse"]?.replace(Regex("""[^0-9]"""), "")?.toIntOrNull()
            ?.takeIf { it in 1..7 }
        val body = attrs["fahrzeugtyp"]?.let { type ->
            when {
                type.contains("kastenwagen", ignoreCase = true) -> BodyType.VAN
                type.contains("transporter", ignoreCase = true) -> BodyType.TRANSPORTER
                type.contains("kombi", ignoreCase = true) -> BodyType.ESTATE
                type.contains("limousine", ignoreCase = true) -> BodyType.SEDAN
                type.contains("cabrio", ignoreCase = true) -> BodyType.CONVERTIBLE
                type.contains("suv", ignoreCase = true) || type.contains("gelände", ignoreCase = true) -> BodyType.SUV
                else -> null
            }
        }
        val colour = attrs["farbe"]?.takeIf { it.length in 2..30 }

        val info = VehicleInfo(
            firstRegYear = year,
            mileageKm = km,
            powerKw = powerKw,
            gearbox = gearbox,
            fuel = fuel,
            bodyType = body,
            emissionClassEuro = emission,
            color = colour,
        )
        // Every value here is stated by the seller in a structured field, so all of it counts as
        // verified rather than read out of prose.
        return VehicleTextParser.verifiedByPresence(info).takeIf {
            year != null || km != null || powerKw != null || gearbox != null || fuel != null
        }
    }
}
