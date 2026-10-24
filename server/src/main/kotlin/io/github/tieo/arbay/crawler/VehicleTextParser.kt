package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.BodyType
import io.github.tieo.arbay.model.Drivetrain
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
    // The figure ends in a digit and stays on one line: "Baujahr September 2018.\n\nKilometerleistung
    // 144.035" is a year closing one sentence and a label opening the next, not 2018 km. A separator
    // only splits thousands, three digits each, and the figure starts where a number starts:
    // "BJ 2018 185000Km" is a year and 185,000 km, not one figure of 2,018,185,000.
    private val mileageRegex = Regex(
        """(?<![0-9])([0-9]{1,3}(?:[. \u00a0][0-9]{3})+|[0-9]{2,})[ \u00a0]?(?:km|kilometer)""",
        RegexOption.IGNORE_CASE,
    )
    // The label written before the figure: "Kilometerleistung 144.035", "Laufleistung: 98.000",
    // "km-Stand 120000", or the unit alone as a field name, "Km: 212.000".
    private val labeledMileageRegex = Regex(
        """\b(?:(?:km|kilometer|lauf)[\s-]?(?:stand|leistung)\b[ \u00a0]*[:.]?|(?:km|kilometer)[ \u00a0]*:)[ \u00a0]*(?:ca\.?[ \u00a0]*)?([0-9]{1,3}(?:[. \u00a0][0-9]{3})+|[0-9]{4,7})(?![0-9])""",
        RegexOption.IGNORE_CASE,
    )
    // The digit lookbehind stops a longer figure's tail from posing as power ("1.200 PS" is
    // not a 200 PS engine, "503.661 km" not a 661 PS one).
    private val kwRegex = Regex("""(?<![\d.,])([0-9]{2,3})\s?kw\b""", RegexOption.IGNORE_CASE)
    // "km" is NOT a power unit — including it read the odometer's last digits as PS.
    private val psRegex = Regex("""(?<![\d.,])([0-9]{2,3})\s?(?:ps|hp|hk)\b""", RegexOption.IGNORE_CASE)
    // The unit written as the label before the figure, as a seller's list does: "PS 180", "PS: 180".
    private val labeledPsRegex = Regex("""\bps\b[ \u00a0]*:?[ \u00a0]*([0-9]{2,3})(?!\d|[.,]\d)""", RegexOption.IGNORE_CASE)
    // The label may introduce a whole date ("EZ. 10.07.2014"), whose day and month come before
    // the year.
    private val yearRegex = Regex("""(?:ez|erstzulassung|first reg\w*|bj\.?|baujahr|reg\.?)\D{0,6}((?:(?:0?[1-9]|[12]\d|3[01])[/.\-])?(?:0?[1-9]|1[0-2])[/.\-])?((?:19|20)\d{2})""", RegexOption.IGNORE_CASE)
    // A whole date with its day ("Service am 20.01.2026") marks something that happened to the
    // van, a service, an inspection, a sale; unlabeled, it never names the first registration.
    private val fullDateYearRegex = Regex("""\b(?:0?[1-9]|[12]\d|3[01])\.(?:0?[1-9]|1[0-2])\.((?:19|20)\d{2})\b""")
    private val bareYearRegex = Regex("""\b(19[89]\d|20[0-3]\d)\b""")
    // Inspection validity ("APK tot 2027", "TÜV 06/2026", "HU bis 2025", a private seller's bare
    // "gültig für 2025") names a deadline, never the first registration; matched year positions
    // are barred from the bare fallback.
    private val inspectionYearRegex = Regex(
        """\b(?:t[üÜu]v|tuev|apk|hu|au|keuring|gekeurd|hauptuntersuchung|g[üÜu]ltig|gueltig)\b\D{0,16}(?:(?:0[1-9]|1[0-2])[/.\-])?((?:19|20)\d{2})""",
        RegexOption.IGNORE_CASE,
    )
    // A year after a piece of upkeep ("Letzter Service in 07.2026", "Zahnriemen neu 2022") dates
    // the work done, not the van.
    private val upkeepYearRegex = Regex(
        """\b(?:service|inspektion|wartung|[öo]e?lwechsel|kundendienst|zahnriemen|steuerkette|kupplung)\b[^\n\d]{0,20}(?:(?:0?[1-9]|1[0-2])[/.\-])?((?:19|20)\d{2})\b""",
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
    // A service log written as odometer readings without a unit, one line each: "237000 Zahnriemen
    // mit Wasserpumpe", "249000 Ölwechsel mit Durchsicht". The work was done at that reading, so the
    // van has at least as many kilometres; a Huchting Crafter whose ad says nothing else ranked with
    // no mileage at all while its log ends at 249,000 km.
    private val serviceLogMileageRegex = Regex(
        """(?m)^[ \t\-*•]*(?:bei[ \t]+)?([0-9]{2,3}[. ]?[0-9]{3})[ \t]*(?:km)?[ \t]*[:\-–]?[ \t]*""" +
            """(?:zahnriemen|steuerkette|(?:ö|oe)lwechsel|inspektion|durchsicht|service|wartung|kupplung|""" +
            """wasserpumpe|lichtmaschine|klima\w*|bremsen|getriebe\w*|turbo\w*|injektor\w*|anlasser|batterie)""",
        RegexOption.IGNORE_CASE,
    )
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

    // A type approval key, "HSN/TSN: 0603/BEV": its letters are a code, and "BEV" there is no
    // battery electric van.
    private val typeKey = Regex("""\b(?:hsn|tsn)\b[\s/:.]*(?:(?:hsn|tsn)\b[\s/:.]*)?\w{3,4}(?:[\s/:,.]+(?:(?:zu\s*)?(?:2|tsn)\b[\s/:.]*)?\w{3}\b)?""", RegexOption.IGNORE_CASE)

    // A dealer's list of search words for its whole stock, on one line after its label: a Greven
    // Automotive Crafter's "Trefwoorden: … Elektrisch - Hybride - 4x4 - AWD - 4WD - L1H1 …" names
    // every drive, fuel and size it sells, none of them this van's.
    private val keywordList = Regex(
        """\b(?:trefwoorden|zoekwoorden|zoektermen|keywords|tags|suchbegriffe|suchw(?:ö|oe)rter|stichw(?:ö|oe)rter|schlagw(?:ö|oe)rter)\s*:[^\n]*""",
        RegexOption.IGNORE_CASE,
    )

    /** [text] without a dealer's list of search words, which states nothing about the vehicle. */
    fun withoutKeywordList(text: String): String = keywordList.replace(text, " ")

    fun parse(raw: String?): VehicleInfo? {
        if (raw.isNullOrBlank()) return null
        val text = typeKey.replace(withoutKeywordList(raw), " ")
        val info = VehicleInfo(
            firstRegYear = parseYear(text),
            mileageKm = parseMileage(text),
            powerKw = parsePowerKw(text),
            displacementCc = parseDisplacement(text),
            // Fuel.parse reads a fuel field, where anything it does not know is some other fuel.
            // In an ad's prose, no fuel word means the text does not say, not that the van runs on
            // something else: read as OTHER, every Crafter whose ad never wrote "Diesel" was
            // dropped by a diesel search.
            fuel = parseFuel(text),
            bodyType = BodyType.parse(text),
            gearbox = parseGearbox(text),
            drivetrain = parseDrivetrain(text),
            wheelbaseMm = parseWheelbaseMm(text),
        )
        return if (info == VehicleInfo()) null else info
    }

    // The drive an ad's words name. A Kleinanzeigen van has no drive field, so its "4MOTION" in the
    // title was all there was to tell, and a front-drive search kept the 4MOTION Crafters it
    // dropped on mobile.de. Dealers cut the word short to fit a title ("MittellangHoch 4Moti").
    // An all-wheel drive word wins: VW's own name for the drive is the surer statement.
    private val allWheelDrive = Regex("""\b(?:4[ -]?motion|4moti\w*|4matic|quattro|allrad\w*|4x4|awd)\b""", RegexOption.IGNORE_CASE)
    private val frontDrive = Regex("""\b(?:fwd|frontantrieb|vorderradantrieb)\b""", RegexOption.IGNORE_CASE)
    private val rearDrive = Regex("""\b(?:rwd|heckantrieb|hinterradantrieb)\b""", RegexOption.IGNORE_CASE)

    private fun parseDrivetrain(text: String): Drivetrain? {
        if (allWheelDrive.containsMatchIn(text)) return Drivetrain.AWD
        val front = frontDrive.containsMatchIn(text)
        val rear = rearDrive.containsMatchIn(text)
        return when {
            front && !rear -> Drivetrain.FWD
            rear && !front -> Drivetrain.RWD
            else -> null
        }
    }

    // Equipment prose names electric parts, never the drive: "Servolenkung elektro-mechanisch",
    // "Fensterheber elektrisch vorn", "Außenspiegel elektrisch verstellbar". Read as a fuel field,
    // each of these made a 2.0 TDI Crafter electric, and a diesel search dropped three dealer vans
    // that stated "Motor 2,0 Ltr. - 103 kW TDI" in the same text.
    private val equipmentElectric = Regex("""\belektro-\w+|\belektrisch\b""", RegexOption.IGNORE_CASE)
    // An engine a combustion fuel names outright; an ad that states one is not about an electric van.
    private val combustionEngine = Regex(
        """\b(?:diesel|tdi|cdi|hdi|dci|crdi|tdci|bluetec|bluehdi|benzin|tsi|tfsi)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** The fuel an ad's prose states, or null where it says none. */
    fun parseFuel(text: String): Fuel? {
        val fuel = Fuel.parse(equipmentElectric.replace(text, " "))?.takeIf { it != Fuel.OTHER }
        if (fuel != Fuel.ELECTRIC || !combustionEngine.containsMatchIn(text)) return fuel
        return Fuel.parse(combustionEngine.findAll(text).joinToString(" ") { it.value })
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

    /**
     * The record with only what a market stated in a field: every value read off text is dropped.
     * Used where a longer text is about to be read, so the guess from a card's one-line summary
     * does not outrank what the whole ad says.
     */
    fun verifiedOnly(v: VehicleInfo?): VehicleInfo? {
        if (v == null) return null
        fun <T> keep(field: VehicleField, value: T?): T? = value.takeIf { v.isVerified(field) }
        return v.copy(
            firstRegYear = keep(VehicleField.FIRST_REG_YEAR, v.firstRegYear),
            firstRegMonth = keep(VehicleField.FIRST_REG_YEAR, v.firstRegMonth),
            mileageKm = keep(VehicleField.MILEAGE, v.mileageKm),
            powerKw = keep(VehicleField.POWER, v.powerKw),
            displacementCc = keep(VehicleField.DISPLACEMENT, v.displacementCc),
            fuel = keep(VehicleField.FUEL, v.fuel),
            bodyType = keep(VehicleField.BODY_TYPE, v.bodyType),
            gearbox = keep(VehicleField.GEARBOX, v.gearbox),
            drivetrain = keep(VehicleField.DRIVETRAIN, v.drivetrain),
            doors = keep(VehicleField.DOORS, v.doors),
            seats = keep(VehicleField.SEATS, v.seats),
            condition = keep(VehicleField.CONDITION, v.condition),
            color = keep(VehicleField.COLOR, v.color),
            emissionClassEuro = keep(VehicleField.EMISSION, v.emissionClassEuro),
            wheelbaseMm = keep(VehicleField.WHEELBASE, v.wheelbaseMm),
        )
    }

    // The mileage in thousands: "Nur 44TKm", "132 TKM", "85 Tsd. km", "120 tausend km", "44,5 tkm".
    private val thousandsKmRegex = Regex(
        """(?<![0-9.,])([0-9]{1,3})(?:[.,]([0-9]))?[ \u00a0]?(?:tkm|tsd\.?[ \u00a0]?km|tausend[ \u00a0]?km)\b""",
        RegexOption.IGNORE_CASE,
    )

    private fun digits(s: String): Int? = s.replace(Regex("""[.\s\u00a0]"""), "").toIntOrNull()

    private fun parseMileage(text: String): Int? {
        // Take the largest plausible match; avoids grabbing "2.0" from an engine size.
        val unitAfter = mileageRegex.findAll(text)
            .filterNot { engineSizeArtifactRegex.matches(it.groupValues[1].trim()) }
            .filterNot { annualKmSuffixRegex.containsMatchIn(text.substring(it.range.last + 1)) }
            .map { it.groupValues[1] }
        val labelBefore = labeledMileageRegex.findAll(text).map { it.groupValues[1] }
        val serviceLog = serviceLogMileageRegex.findAll(text).map { it.groupValues[1] }
        val inThousands = thousandsKmRegex.findAll(text).map { m ->
            (m.groupValues[1].toInt() * 1000 + (m.groupValues[2].toIntOrNull() ?: 0) * 100).toString()
        }
        return (unitAfter + labelBefore + serviceLog + inThousands)
            .mapNotNull { digits(it) }
            .filter { it in 1..2_000_000 }
            .maxOrNull()
    }

    private fun parsePowerKw(text: String): Int? {
        kwRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it in 20..1000) return it }
        // PS/HP fallback → kW (1 PS = 0.7355 kW)
        psRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (it in 30..1500) return (it * 0.7355).toInt()
        }
        labeledPsRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
            if (it in 30..1500) return (it * 0.7355).toInt()
        }
        return null
    }

    private fun parseYear(text: String): Int? {
        yearRegex.find(text)?.groupValues?.get(2)?.toIntOrNull()?.let { return it }
        // Positions of inspection years, dated events and euro prices; those digits never count as a
        // registration year in the unlabeled fallback.
        val excludedStarts = buildSet {
            inspectionYearRegex.findAll(text).forEach { m -> m.groups[1]?.let { add(it.range.first) } }
            fullDateYearRegex.findAll(text).forEach { m -> m.groups[1]?.let { add(it.range.first) } }
            upkeepYearRegex.findAll(text).forEach { m -> m.groups[1]?.let { add(it.range.first) } }
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
        return SellersWords.apply(listing.copy(vehicle = merged))
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
            // Metres with no unit are written to the centimetre, "3,64"; a number with one decimal
            // right behind the word is the engine, "mittlerer Radstand 2.0 TDI", which read as a
            // two metre wheelbase.
            if (unit.isEmpty() && value < 10 && !Regex("""[.,]\d{2,3}$""").containsMatchIn(raw)) return@firstNotNullOfOrNull null
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

    // The gearbox field a dealer's export writes into the ad text, "Getriebe: Schaltgetriebe".
    private val gearboxField = Regex("""\bgetriebe:\s*(halbautomatik|automatik|schaltgetriebe|manuell)""", RegexOption.IGNORE_CASE)

    // Equipment that is automatic without being the gearbox: "Start-Stopp-Automatik",
    // "Klimaautomatik", "Fahrlichtschaltung automatisch", "automatische Distanzregelung". Left in,
    // each of them made a manual van read as an automatic.
    private val notTheGearbox = Regex(
        """start[\s/-]*stopp?[\s-]*automatik|klimaautomatik|automatisch\w*|automatische\w*""",
        RegexOption.IGNORE_CASE,
    )

    // A gearbox named by its gears alone, as VW's equipment lists name the manual ("Getriebe
    // 6-Gang", "6-Gang-Getriebe"); an automatic's line says so ("8-Gang-Automatik"), read first.
    private val gearsOnly = Regex("""\bgetriebe:?\s*\d-gang\b|\b\d-gang-?getriebe\b""", RegexOption.IGNORE_CASE)

    private fun parseGearbox(text: String): Transmission? {
        gearboxField.find(text)?.groupValues?.get(1)?.lowercase()?.let {
            return if (it == "schaltgetriebe" || it == "manuell") Transmission.MANUAL else Transmission.AUTOMATIC
        }
        val s = notTheGearbox.replace(text, " ").lowercase()
        return when {
            "automat" in s || "automatik" in s || "automatic" in s || "dsg" in s || "tiptronic" in s ||
                "s tronic" in s || "s-tronic" in s || "pdk" in s -> Transmission.AUTOMATIC
            "schaltg" in s || "manuell" in s || "manual" in s || "handgeschakeld" in s || gearsOnly.containsMatchIn(s) -> Transmission.MANUAL
            else -> null
        }
    }
}
