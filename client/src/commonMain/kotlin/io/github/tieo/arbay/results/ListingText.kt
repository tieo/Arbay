package io.github.tieo.arbay.results

import io.github.tieo.arbay.grouped
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VanSize
import io.github.tieo.arbay.model.VehicleField
import io.github.tieo.arbay.model.VehicleInfo
import io.github.tieo.arbay.monthYear
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

// How a listing is put into words, the same wherever it is drawn: the phone's cards and sheets and
// the browser's rows and panes read their lines from here.

/** One fact on a listing's line, and whether the market stated it or it was read out of the words.
 *  A read value is shown marked ("~130 kW"), since it is a guess printed beside facts. */
data class Spec(val text: String, val stated: Boolean) {
    override fun toString() = if (stated) text else "~$text"
}

/** Human age of a listing from its posting date ("today", "3 days ago", …); null if in the future
 *  or the date is implausible. */
fun ageLabel(posted: Instant): String? {
    val days = (Clock.System.now() - posted).inWholeDays
    return when {
        days < 0 -> null
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "$days days ago"
        days < 30 -> "${days / 7} wk ago"
        days < 365 -> "${days / 30} mo ago"
        else -> "${days / 365} yr ago"
    }
}

/** AutoScout24 single-letter codes and German/English country names → ISO-2. */
fun normalizeCountry(c: String): String? = when (c.trim().uppercase()) {
    "D", "DE", "DEUTSCHLAND", "GERMANY" -> "DE"
    "A", "AT", "ÖSTERREICH", "OESTERREICH", "AUSTRIA" -> "AT"
    "CH", "SCHWEIZ", "SWITZERLAND", "SUISSE" -> "CH"
    "F", "FR", "FRANKREICH", "FRANCE" -> "FR"
    "I", "IT", "ITALIEN", "ITALY", "ITALIA" -> "IT"
    "E", "ES", "SPANIEN", "SPAIN" -> "ES"
    "NL", "NIEDERLANDE", "NETHERLANDS" -> "NL"
    "B", "BE", "BELGIEN", "BELGIUM" -> "BE"
    "L", "LU", "LUXEMBURG", "LUXEMBOURG" -> "LU"
    "PL", "POLEN", "POLAND" -> "PL"
    "CZ", "TSCHECHIEN", "CZECHIA" -> "CZ"
    "DK", "DÄNEMARK", "DENMARK" -> "DK"
    "SE", "SCHWEDEN", "SWEDEN" -> "SE"
    else -> c.trim().takeIf { it.length == 2 && it.all { ch -> ch.isLetter() } }?.uppercase()
}

/** The country a listing is sourced from, as ISO-2, for the cross-border origin badge. Prefers the
 *  listing's own location (multi-country platforms like AutoScout24 mix markets), else the platform's
 *  home country. Returns null for the home market (DE), which gets no badge, and for unknown origins. */
fun originCountry(listing: Listing): String? {
    val iso = listing.location?.country?.let { normalizeCountry(it) } ?: listing.platformId.country
    return iso?.uppercase()?.takeUnless { it == "DE" }
}

/** Two-letter ISO country code → its flag emoji (regional-indicator pair). "DK" → 🇩🇰.
 *  Each letter maps to a code point above U+FFFF, so it's emitted as a UTF-16 surrogate pair. */
fun flagEmoji(cc: String): String {
    if (cc.length != 2) return ""
    return buildString {
        for (c in cc.uppercase()) {
            if (c !in 'A'..'Z') return ""
            val cp = 0x1F1E6 + (c - 'A')
            val offset = cp - 0x10000
            append((0xD800 + (offset shr 10)).toChar())
            append((0xDC00 + (offset and 0x3FF)).toChar())
        }
    }
}

/** Where a listing is offered: its market with the origin's flag when that is abroad, and every
 *  other market carrying the same offer. */
fun sourceLabel(listing: Listing, elsewhere: List<Listing> = emptyList()): String {
    val own = originCountry(listing)?.let { "${listing.platformId.displayName} ${flagEmoji(it)}" }
        ?: listing.platformId.displayName
    return own + elsewhere.map { it.platformId }.distinct().filter { it != listing.platformId }
        .joinToString("") { " + ${it.displayName}" }
}

/** What the listing is and states, in the order a card reads it: sold, condition, then the vehicle's
 *  registration, mileage, power, gearbox, fuel and van size. The source is not part of it. */
fun listingSpecs(listing: Listing): List<Spec> = buildList {
    if (listing.sold) add(Spec("sold", true))
    listing.condition?.takeIf { !listing.sold }?.let {
        add(Spec(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.replace("_", " "), true))
    }
    listing.vehicle?.let { addAll(vehicleSpecs(it)) }
}

private fun vehicleSpecs(v: VehicleInfo): List<Spec> = buildList {
    v.firstRegYear?.let {
        val ym = v.firstRegMonth?.let { month -> monthYear(month, it) } ?: it.toString()
        add(Spec(ym, v.isVerified(VehicleField.FIRST_REG_YEAR)))
    }
    v.mileageKm?.let { add(Spec("${grouped(it.toLong())} km", v.isVerified(VehicleField.MILEAGE))) }
    v.powerKw?.let { add(Spec("$it kW", v.isVerified(VehicleField.POWER))) }
    v.gearbox?.let {
        add(Spec(if (it == Transmission.AUTOMATIC) "Automatik" else "Schaltgetriebe", v.isVerified(VehicleField.GEARBOX)))
    }
    v.fuel?.takeIf { it != Fuel.OTHER }?.let {
        add(Spec(it.name.lowercase().replaceFirstChar { c -> c.uppercase() }, v.isVerified(VehicleField.FUEL)))
    }
    v.vanLength?.let { add(Spec(VanSize.lengthLabel(it), v.isVerified(VehicleField.VAN_LENGTH))) }
    v.vanHeight?.let { add(Spec(VanSize.roofLabel(it), v.isVerified(VehicleField.VAN_HEIGHT))) }
}

/** Where it is, how far, how old, how well it fits: the line under a listing's specs. */
fun listingFoot(listing: Listing): List<String> = buildList {
    listing.location?.let { loc ->
        val place = loc.raw ?: listOfNotNull(loc.zip, loc.city).joinToString(" ")
        if (place.isNotBlank()) add(place)
    }
    listing.distanceKm?.let { add("${it.roundToInt()} km away") }
    listing.listingDate?.let { posted -> ageLabel(posted)?.let { add(it) } }
    listing.matchScore?.let { add("${(it * 100).roundToInt()}% match") }
}

/** One row of a listing's full description of the vehicle: what it is called, its value, and the
 *  field it came from, so a value the market stated and one read out of its words differ. */
data class DetailSpec(val label: String, val value: String, val field: VehicleField? = null) {
    fun stated(vehicle: VehicleInfo): Boolean = field == null || vehicle.isVerified(field)
}

/** Everything the market published about the vehicle itself, in reading order. */
fun detailSpecs(v: VehicleInfo): List<DetailSpec> = buildList {
    v.firstRegYear?.let {
        add(DetailSpec("First registered", v.firstRegMonth?.let { m -> monthYear(m, it) } ?: "$it", VehicleField.FIRST_REG_YEAR))
    }
    v.mileageKm?.let { add(DetailSpec("Mileage", "${grouped(it.toLong(), '.')} km", VehicleField.MILEAGE)) }
    v.powerKw?.let { add(DetailSpec("Power", "$it kW · ${(it * 1.35962).toInt()} hp", VehicleField.POWER)) }
    v.displacementCc?.let { add(DetailSpec("Engine", "$it cc", VehicleField.DISPLACEMENT)) }
    v.fuel?.let { add(DetailSpec("Fuel", it.name.lowercase().replace('_', ' '), VehicleField.FUEL)) }
    v.gearbox?.let { add(DetailSpec("Gearbox", it.name.lowercase(), VehicleField.GEARBOX)) }
    v.drivetrain?.let { add(DetailSpec("Drive", it.name.lowercase().replace('_', ' '), VehicleField.DRIVETRAIN)) }
    v.bodyType?.let { add(DetailSpec("Body", it.name.lowercase().replace('_', ' '), VehicleField.BODY_TYPE)) }
    v.doors?.let { add(DetailSpec("Doors", "$it", VehicleField.DOORS)) }
    v.seats?.let { add(DetailSpec("Seats", "$it", VehicleField.SEATS)) }
    v.condition?.let { add(DetailSpec("Condition", it.name.lowercase().replace('_', ' '), VehicleField.CONDITION)) }
    v.previousOwners?.let { add(DetailSpec("Previous owners", "$it")) }
    v.color?.let { add(DetailSpec("Colour", it, VehicleField.COLOR)) }
    v.emissionClassEuro?.let { add(DetailSpec("Emission class", "Euro $it", VehicleField.EMISSION)) }
    v.emissionSticker?.let { add(DetailSpec("Sticker", "$it", VehicleField.EMISSION_STICKER)) }
    v.inspectionUntil?.let { add(DetailSpec("Inspection until", it, VehicleField.INSPECTION)) }
    v.upholstery?.let { add(DetailSpec("Upholstery", it, VehicleField.UPHOLSTERY)) }
    v.vanLength?.let { add(DetailSpec("Length", VanSize.lengthLabel(it), VehicleField.VAN_LENGTH)) }
    v.vanHeight?.let { add(DetailSpec("Roof", VanSize.roofLabel(it), VehicleField.VAN_HEIGHT)) }
    v.wheelbaseMm?.let { add(DetailSpec("Wheelbase", "$it mm", VehicleField.WHEELBASE)) }
}

/** What a condition is called on a chip and in a sentence. */
val Condition.label: String
    get() = when (this) {
        Condition.NEW -> "New"
        Condition.LIKE_NEW -> "Like new"
        Condition.VERY_GOOD -> "Very good"
        Condition.GOOD -> "Good"
        Condition.ACCEPTABLE -> "Acceptable"
        Condition.USED -> "Used"
        Condition.REFURBISHED -> "Refurbished"
        Condition.PARTS_ONLY -> "For parts"
    }
