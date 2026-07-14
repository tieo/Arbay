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
    private val kwRegex = Regex("""([0-9]{2,3})\s?kw""", RegexOption.IGNORE_CASE)
    private val psRegex = Regex("""([0-9]{2,3})\s?(?:ps|hp|hk|km\b)""", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("""(?:ez|erstzulassung|first reg\w*|bj\.?|baujahr|reg\.?)\D{0,6}((?:0[1-9]|1[0-2])[/.\-])?((?:19|20)\d{2})""", RegexOption.IGNORE_CASE)
    private val bareYearRegex = Regex("""\b(19[89]\d|20[0-3]\d)\b""")
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
        }
        return v.copy(verified = fields)
    }

    /** Merge parsed text values under authoritative structured ones (structured wins for
     *  both value and verification). Text-only fields stay inferred, never verified. */
    fun merge(structured: VehicleInfo?, fromText: VehicleInfo?): VehicleInfo? {
        if (structured == null) return fromText
        if (fromText == null) return structured
        return VehicleInfo(
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
            // Only the structured side's fields are verified; text fills gaps as inferred.
            verified = structured.verified,
        )
    }

    private fun digits(s: String): Int? = s.replace(Regex("""[.\s]"""), "").toIntOrNull()

    private fun parseMileage(text: String): Int? {
        // Take the largest plausible match; avoids grabbing "2.0" from an engine size.
        return mileageRegex.findAll(text)
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
        return bareYearRegex.findAll(text).mapNotNull { it.value.toIntOrNull() }
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
