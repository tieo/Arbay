package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VehicleCondition
import io.github.tieo.arbay.model.VehicleInfo
import org.jsoup.Jsoup

/**
 * Parses the full structured attribute table on a Kleinanzeigen car detail page
 * (`li.addetailslist--detail` with a `--detail--value` span) into a verified VehicleInfo.
 * These are the site's own declared specs — power, gearbox, fuel, doors, emission class and
 * sticker, colour, upholstery, HU date — so every field parsed here is treated as verified
 * and may enforce a filter, unlike the regex guesses over the search-card text.
 */
object KleinanzeigenDetailParser {

    private val months = mapOf(
        "januar" to 1, "februar" to 2, "märz" to 3, "maerz" to 3, "april" to 4, "mai" to 5,
        "juni" to 6, "juli" to 7, "august" to 8, "september" to 9, "oktober" to 10,
        "november" to 11, "dezember" to 12,
    )

    fun parse(html: String): VehicleInfo? {
        val doc = Jsoup.parse(html)
        val attrs = HashMap<String, String>()
        for (li in doc.select("li.addetailslist--detail")) {
            val value = li.selectFirst("span.addetailslist--detail--value")?.text()?.trim() ?: continue
            // The label is the li's own text minus the value span.
            val label = li.ownText().trim().ifBlank {
                li.text().removeSuffix(value).trim()
            }
            if (label.isNotBlank() && value.isNotBlank()) attrs[label.lowercase()] = value
        }
        // The wheelbase is never one of the attribute rows here: where a seller states it at all,
        // it is a line of their own prose ("Radstand: 3.250 mm"), so it is read off the whole page
        // and stays inferred rather than verified.
        val wheelbase = VehicleTextParser.parse(doc.text())?.wheelbaseMm
        if (attrs.isEmpty()) return wheelbase?.let { VehicleInfo(wheelbaseMm = it) }

        val (year, month) = parseGermanMonthYear(attrs["erstzulassung"])
        val info = VehicleInfo(
            firstRegYear = year,
            firstRegMonth = month,
            mileageKm = attrs["kilometerstand"]?.replace(Regex("""[^0-9]"""), "")?.toIntOrNull()
                ?.takeIf { it in 1..2_000_000 },
            powerKw = attrs["leistung"]?.let { Regex("""(\d+)\s*PS""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                ?.let { (it * 0.7355).toInt() }?.takeIf { it in 20..1000 },
            fuel = Fuel.parse(attrs["kraftstoffart"]),
            gearbox = when {
                attrs["getriebe"]?.contains("automat", true) == true -> Transmission.AUTOMATIC
                attrs["getriebe"]?.contains("manuell", true) == true ||
                    attrs["getriebe"]?.contains("schaltg", true) == true -> Transmission.MANUAL
                else -> null
            },
            condition = attrs["fahrzeugzustand"]?.let {
                when {
                    it.contains("unbeschädigt", true) -> VehicleCondition.USED
                    it.contains("beschädigt", true) || it.contains("unfall", true) -> VehicleCondition.DAMAGED
                    else -> VehicleCondition.parse(it)
                }
            },
            // "2/3", "4/5" buckets — take the lower bound.
            doors = attrs["anzahl türen"]?.let { Regex("""(\d)""").find(it)?.groupValues?.get(1)?.toIntOrNull() },
            emissionClassEuro = attrs["schadstoffklasse"]?.let {
                Regex("""euro\s*(\d)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull()
            },
            emissionSticker = attrs["umweltplakette"]?.let { Regex("""(\d)""").find(it)?.groupValues?.get(1)?.toIntOrNull() },
            color = attrs["außenfarbe"]?.takeIf { it.isNotBlank() },
            upholstery = attrs["material innenausstattung"]?.takeIf { it.isNotBlank() },
            inspectionUntil = parseGermanMonthYear(attrs["hu bis"]).let { (y, m) ->
                if (y != null) "%04d-%02d".format(y, m ?: 1) else null
            },
        )
        return VehicleTextParser.verifiedByPresence(info).copy(wheelbaseMm = wheelbase)
    }

    /** "November 2012" -> (2012, 11); "2019" -> (2019, null). */
    private fun parseGermanMonthYear(text: String?): Pair<Int?, Int?> {
        if (text == null) return null to null
        val year = Regex("""(19|20)\d{2}""").find(text)?.value?.toIntOrNull()
        val month = months.entries.firstOrNull { text.contains(it.key, ignoreCase = true) }?.value
        return year to month
    }
}
