package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.BodyType
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VehicleField
import io.github.tieo.arbay.model.VehicleInfo

/**
 * Best-effort structured vehicle attributes from the free text a listing already carries
 * (title, subtitle, description, data attributes). Every crawler can call this to get a
 * baseline VehicleInfo; crawlers with structured JSON enrich the result with exact values.
 *
 * This is what makes cross-platform post-filtering possible: sites that can't filter by
 * mileage/power/year server-side (Kleinanzeigen, eBay) still yield those numbers here, so
 * the facet engine can enforce the filter and drop non-matching listings.
 */
object VehicleTextParser {
    private val mileageRegex = Regex("""([0-9][0-9.\s]{2,})\s?(?:km|kilometer)""", RegexOption.IGNORE_CASE)
    // The digit lookbehind stops a longer figure's tail from posing as power ("1.200 PS" is
    // not a 200 PS engine, "503.661 km" not a 661 PS one).
    private val kwRegex = Regex("""(?<![\d.,])([0-9]{2,3})\s?kw\b""", RegexOption.IGNORE_CASE)
    // "km" is NOT a power unit — including it read the odometer's last digits as PS.
    private val psRegex = Regex("""(?<![\d.,])([0-9]{2,3})\s?(?:ps|hp|hk)\b""", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("""(?:ez|erstzulassung|first reg\w*|bj\.?|baujahr|reg\.?)\D{0,6}((?:0[1-9]|1[0-2])[/.\-])?((?:19|20)\d{2})""", RegexOption.IGNORE_CASE)
    private val bareYearRegex = Regex("""\b(19[89]\d|20[0-3]\d)\b""")
    // Inspection validity ("APK tot 2027", "TÜV 06/2026", "HU bis 2025") names a deadline,
    // never the first registration; matched year positions are barred from the bare fallback.
    private val inspectionYearRegex = Regex(
        """\b(?:t[üÜu]v|tuev|apk|hu|au|keuring|gekeurd|hauptuntersuchung)\b\D{0,16}(?:(?:0[1-9]|1[0-2])[/.\-])?((?:19|20)\d{2})""",
        RegexOption.IGNORE_CASE,
    )
    // A year-shaped number next to a currency mark is a price ("Preis 2000 €"), not a year.
    private val priceYearRegex = Regex(
        """(?:€|\beuro?\b)\s?((?:19|20)\d{2})\b|\b((?:19|20)\d{2})\s?(?:€|euro?\b|,-)""",
        RegexOption.IGNORE_CASE,
    )
    // A single-decimal figure before "km" is an engine size artifact ("2.0 km-Stand"), never
    // an odometer reading.
    private val engineSizeArtifactRegex = Regex("""^\d[.,]\d$""")
    // Text right after a km figure that marks it as a lease's annual allowance, not an odometer.
    private val annualKmSuffixRegex = Regex(
        """^\s*(?:/\s?(?:jahr|year|jaar)|pro\s+jahr|per\s+jaar|p\.\s?a\.|im\s+jahr|j[äÄa]hrlich|frei\b)""",
        RegexOption.IGNORE_CASE,
    )
    /**
     * Every way an ad names the distance between the axles, and the number after it.
     *
     * German dealers write "Radstand", "Radabstand" and "Achsabstand" for the same measurement, and
     * a list that had only the first one read 4490 mm as nothing on an ad that stated it plainly.
     * The unit is optional because ads write "Radstand 3640", "3,64 m" and "364 cm" as well.
     */
    private val wheelbaseRegex = Regex(
        """(?:rad(?:ab)?stand|achs(?:ab)?stand|wheel\s?base|empattement|interasse|""" +
            """batalla|rozstaw\s+osi|wielbasis|rozvor)""" +
            // What a dealer writes between the word and the number: a colon, a dash, and the name
            // of the variant it belongs to — "Radstand lang (LR) - 4.490 mm". Anything with a
            // digit in it ends the run, so the number found is the one this label introduces.
            """([^0-9<>\n]{0,25}?)([0-9][0-9.,]{1,7})\s*(mm|cm|m)?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val displacementCcRegex = Regex("""([0-9]{3,4})\s?(?:cm³|ccm|cc)\b""", RegexOption.IGNORE_CASE)
    private val displacementLRegex = Regex("""\b([0-9])[.,]([0-9])\s?(?:l|liter|litre)\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String?): VehicleInfo? {
        if (text.isNullOrBlank()) return null
        val info = VehicleInfo(
            firstRegYear = parseYear(text),
            mileageKm = parseMileage(text),
            powerKw = parsePowerKw(text),
            displacementCc = parseDisplacement(text),
            fuel = Fuel.parse(text),
            bodyType = BodyType.parse(text),
            gearbox = parseGearbox(text),
            wheelbaseMm = parseWheelbaseMm(text),
        )
        return if (info == VehicleInfo()) null else info
    }

    /** Marks a purely-structured record's present fields as verified — for crawlers that
     *  build VehicleInfo straight from the site's JSON/labeled attributes. */
    fun verifiedByPresence(v: VehicleInfo): VehicleInfo {
        val fields = buildSet {
            if (v.firstRegYear != null) add(VehicleField.FIRST_REG_YEAR)
            if (v.mileageKm != null) add(VehicleField.MILEAGE)
            if (v.powerKw != null) add(VehicleField.POWER)
            if (v.displacementCc != null) add(VehicleField.DISPLACEMENT)
            if (v.fuel != null) add(VehicleField.FUEL)
            if (v.bodyType != null) add(VehicleField.BODY_TYPE)
            if (v.gearbox != null) add(VehicleField.GEARBOX)
            if (v.drivetrain != null) add(VehicleField.DRIVETRAIN)
            if (v.doors != null) add(VehicleField.DOORS)
            if (v.seats != null) add(VehicleField.SEATS)
            if (v.condition != null) add(VehicleField.CONDITION)
            if (v.color != null) add(VehicleField.COLOR)
            if (v.emissionClassEuro != null) add(VehicleField.EMISSION)
            if (v.emissionSticker != null) add(VehicleField.EMISSION_STICKER)
            if (v.inspectionUntil != null) add(VehicleField.INSPECTION)
            if (v.upholstery != null) add(VehicleField.UPHOLSTERY)
            if (v.wheelbaseMm != null) add(VehicleField.WHEELBASE)
        }
        return v.copy(verified = fields)
    }

    /** Merge parsed text values under authoritative structured ones (structured wins for
     *  both value and verification). Text-only fields stay inferred, never verified. */
    fun merge(structured: VehicleInfo?, fromText: VehicleInfo?): VehicleInfo? {
        if (structured == null) return fromText
        if (fromText == null) return structured
        // Built from the structured record so a field only it carries — the inspection date, the
        // upholstery, the van's size codes — survives the merge. Listing them one by one dropped
        // whichever the text side has no counterpart for.
        return structured.copy(
            firstRegYear = structured.firstRegYear ?: fromText.firstRegYear,
            firstRegMonth = structured.firstRegMonth ?: fromText.firstRegMonth,
            mileageKm = structured.mileageKm ?: fromText.mileageKm,
            powerKw = structured.powerKw ?: fromText.powerKw,
            displacementCc = structured.displacementCc ?: fromText.displacementCc,
            fuel = structured.fuel ?: fromText.fuel,
            bodyType = structured.bodyType ?: fromText.bodyType,
            gearbox = structured.gearbox ?: fromText.gearbox,
            drivetrain = structured.drivetrain ?: fromText.drivetrain,
            doors = structured.doors ?: fromText.doors,
            seats = structured.seats ?: fromText.seats,
            condition = structured.condition ?: fromText.condition,
            previousOwners = structured.previousOwners ?: fromText.previousOwners,
            color = structured.color ?: fromText.color,
            emissionClassEuro = structured.emissionClassEuro ?: fromText.emissionClassEuro,
            wheelbaseMm = structured.wheelbaseMm ?: fromText.wheelbaseMm,
            // Only the structured side's fields are verified; text fills gaps as inferred.
            verified = structured.verified,
        )
    }

    private fun digits(s: String): Int? = s.replace(Regex("""[.\s]"""), "").toIntOrNull()

    private fun parseMileage(text: String): Int? {
        // Take the largest plausible match; avoids grabbing "2.0" from an engine size.
        return mileageRegex.findAll(text)
            .filterNot { engineSizeArtifactRegex.matches(it.groupValues[1].trim()) }
            .filterNot { annualKmSuffixRegex.containsMatchIn(text.substring(it.range.last + 1)) }
            .mapNotNull { digits(it.groupValues[1]) }
            .filter { it in 1..2_000_000 }
            .maxOrNull()
    }

    private fun parsePowerKw(text: String): Int? {
        kwRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 20..1000) return it }
        // PS/HP fallback → kW (1 PS = 0.7355 kW)
        psRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (it in 30..1500) return (it * 0.7355).toInt()
        }
        return null
    }

    private fun parseYear(text: String): Int? {
        yearRegex.find(text)?.groupValues?.get(2)?.toIntOrNull()?.let { return it }
        // Positions of inspection years and euro prices; those digits never count as a
        // registration year in the unlabeled fallback.
        val excludedStarts = buildSet {
            inspectionYearRegex.findAll(text).forEach { m -> m.groups[1]?.let { add(it.range.first) } }
            priceYearRegex.findAll(text).forEach { m ->
                m.groups[1]?.let { add(it.range.first) }
                m.groups[2]?.let { add(it.range.first) }
            }
        }
        return bareYearRegex.findAll(text)
            .filter { it.range.first !in excludedStarts }
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1980..2035 }.maxOrNull()
    }

    private fun parseDisplacement(text: String): Int? {
        displacementCcRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 600..8000) return it }
        displacementLRegex.find(text)?.let {
            val cc = (it.groupValues[1] + it.groupValues[2]).toIntOrNull()?.times(100)
            if (cc != null && cc in 600..8000) return cc
        }
        return null
    }

    /** Fill a car listing's VehicleInfo from its own text, keeping any structured values a
     *  crawler already set. Applied to car-search results so every platform is post-filterable. */
    fun enrich(listing: Listing): Listing {
        val fromText = parse("${listing.title} ${listing.description ?: ""}")
        val merged = merge(listing.vehicle, fromText) ?: return listing
        return listing.copy(vehicle = merged)
    }

    /**
     * The wheelbase a listing states, in millimetres.
     *
     * No market has a field for it that its sellers fill in — AutoScout24 ships a `wheelBase` key
     * and it was empty on every Crafter measured — so the number lives in the equipment prose,
     * written as "Radstand 3640 mm" or "Radstand: 3.250 mm". Bounded to what a road vehicle can
     * have, so a stray four-digit number in the same sentence cannot become a wheelbase.
     */
    fun parseWheelbaseMm(text: String): Int? =
        wheelbaseRegex.findAll(text).firstNotNullOfOrNull { m ->
            val between = m.groupValues[1]
            val raw = m.groupValues[2]
            val unit = m.groupValues[3].lowercase()
            // A number reached across words has to name its unit. Measured over 72 van ads, the
            // number always sits right behind the word ("Radstand 3665 mm") or behind the variant
            // it belongs to ("Radstand lang (LR) - 4.490 mm"); a bare number several words later
            // is some other figure in the same sentence.
            if (unit.isEmpty() && between.trim().length > 2) return@firstNotNullOfOrNull null
            // "3.640" and "3,640" are one number with a thousands mark; "3,64" and "3.64" are
            // metres written with either mark, which is which decided by what follows the mark.
            val grouped = Regex("""^\d{1,2}[.,]\d{3}$""").matches(raw)
            val asNumber = raw.replace(",", ".").let { if (grouped) it.replace(".", "") else it }
            val value = asNumber.toDoubleOrNull() ?: return@firstNotNullOfOrNull null
            val mm = when {
                unit == "mm" -> value
                unit == "cm" -> value * 10
                unit == "m" -> value * 1000
                // No unit: the size of the number says which it is, since a wheelbase is between
                // one and a half and seven metres however it is written.
                value < 10 -> value * 1000
                value < 800 -> value * 10
                else -> value
            }
            mm.toInt().takeIf { it in 1500..7000 }
        }

    private fun parseGearbox(text: String): Transmission? {
        val s = text.lowercase()
        return when {
            "automat" in s || "automatik" in s || "automatic" in s || "dsg" in s || "tiptronic" in s ||
                "s tronic" in s || "s-tronic" in s || "pdk" in s -> Transmission.AUTOMATIC
            "schaltg" in s || "manuell" in s || "manual" in s || "handgeschakeld" in s -> Transmission.MANUAL
            else -> null
        }
    }
}
