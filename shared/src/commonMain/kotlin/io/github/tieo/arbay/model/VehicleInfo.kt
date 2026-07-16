package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

/** Every filterable vehicle attribute, used to record which fields are verified. */
@Serializable
enum class VehicleField {
    FIRST_REG_YEAR, MILEAGE, POWER, DISPLACEMENT, FUEL, BODY_TYPE, GEARBOX,
    DRIVETRAIN, DOORS, SEATS, CONDITION, COLOR, EMISSION,
    EMISSION_STICKER, INSPECTION, UPHOLSTERY,
}

/**
 * Normalized vehicle attributes parsed from a listing, independent of the source site's
 * language or encoding. This is the substrate for post-filtering and faceting: filters a
 * marketplace can't apply server-side (e.g. mileage on Kleinanzeigen) are enforced against
 * these fields instead, and per-filter hidden counts are computed from them.
 *
 * Every field is nullable — a site may not expose it, and a null means "unknown", never
 * "excluded". A filter treats unknown as a soft pass unless the user opts to hide unknowns.
 *
 * [verified] records which fields came from the site's own structured data (a JSON field, a
 * labeled attribute, a spec chip) rather than a regex guess over the title/description. Only
 * verified fields may exclude a listing — a text-inferred value must never drop a real match,
 * because a misread number would silently discard a car that actually fits. Inferred values
 * are display-only and mark the listing as "unverified" for that field.
 */
@Serializable
data class VehicleInfo(
    val firstRegYear: Int? = null,
    val firstRegMonth: Int? = null,
    val mileageKm: Int? = null,
    val powerKw: Int? = null,
    val displacementCc: Int? = null,
    val fuel: Fuel? = null,
    val bodyType: BodyType? = null,
    val gearbox: Transmission? = null,
    val drivetrain: Drivetrain? = null,
    val doors: Int? = null,
    val seats: Int? = null,
    val condition: VehicleCondition? = null,
    val previousOwners: Int? = null,
    val color: String? = null,
    val emissionClassEuro: Int? = null,
    val emissionSticker: Int? = null,      // Umweltplakette 1-4 (green = 4)
    val inspectionUntil: String? = null,   // HU/TÜV valid until, "YYYY-MM"
    val upholstery: String? = null,        // Material Innenausstattung (Stoff/Leder/…)
    val verified: Set<VehicleField> = emptySet(),
) {
    fun isVerified(field: VehicleField): Boolean = field in verified
}

@Serializable
enum class Fuel {
    PETROL, DIESEL, ELECTRIC, HYBRID_PETROL, HYBRID_DIESEL, PLUGIN_HYBRID,
    MILD_HYBRID, LPG, CNG, HYDROGEN, ETHANOL, OTHER;

    companion object {
        fun parse(raw: String?): Fuel? {
            val s = raw?.lowercase()?.trim() ?: return null
            return when {
                s.isBlank() -> null
                "plug" in s || "phev" in s || "laddhybrid" in s || "plug-in" in s -> PLUGIN_HYBRID
                "mild" in s || "mhev" in s -> MILD_HYBRID
                "hybrid" in s || "hybride" in s || "hybridní" in s ->
                    if ("diesel" in s || "nafta" in s) HYBRID_DIESEL else HYBRID_PETROL
                // Word-boundary match: "Elektro"/"Elektrisch"/"electric"/"BEV" as a fuel, but NOT
                // "elektrische Fensterheber" (electric windows) and other equipment adjectives.
                Regex("""\b(elektro\w*|elektrisch|electric|bev)\b""").containsMatchIn(s) || s == "el" -> ELECTRIC
                "diesel" in s || "nafta" in s || "tdi" in s || "hdi" in s || "cdi" in s ||
                    "dci" in s || "bluetec" in s || "crdi" in s || "tdci" in s || "jtd" in s ||
                    "bluehdi" in s || "d4d" in s -> DIESEL
                "benzin" in s || "petrol" in s || "benzine" in s || "gasoline" in s || "benzín" in s ||
                    "tsi" in s || "tfsi" in s || "fsi" in s || "gti" in s || "vtec" in s ||
                    "thp" in s || "mpi" in s || "vvt" in s -> PETROL
                "lpg" in s || "autogas" in s || "flüssiggas" in s -> LPG
                "cng" in s || "erdgas" in s || "aardgas" in s -> CNG
                "wasserstoff" in s || "hydrogen" in s || "waterstof" in s || "vodík" in s -> HYDROGEN
                "ethanol" in s || "e85" in s -> ETHANOL
                else -> OTHER
            }
        }
    }
}

@Serializable
enum class BodyType {
    SMALL_CAR, SEDAN, ESTATE, SUV, COUPE, CONVERTIBLE, VAN, MINIVAN, PICKUP, TRANSPORTER, OTHER;

    companion object {
        fun parse(raw: String?): BodyType? {
            val s = raw?.lowercase()?.trim() ?: return null
            return when {
                s.isBlank() -> null
                "cabrio" in s || "convertible" in s || "roadster" in s || "kabriolet" in s -> CONVERTIBLE
                "coupe" in s || "coupé" in s || "kupé" in s || "kupe" in s -> COUPE
                "suv" in s || "geländ" in s || "gelaend" in s || "offroad" in s || "off-road" in s ||
                    "terräng" in s || "terén" in s || "crossover" in s || "cuv" in s -> SUV
                "pickup" in s || "pick-up" in s || "pick up" in s -> PICKUP
                "transporter" in s || "kasten" in s || "panel van" in s || "box van" in s ||
                    "dostawcze" in s || "užitkov" in s || "uzitkov" in s || "transportbil" in s -> TRANSPORTER
                "kombi" in s || "estate" in s || "stationc" in s || "stationw" in s || "station wagon" in s ||
                    "shooting brake" in s -> ESTATE
                "van" in s || "mpv" in s || "kleinbus" in s || "minivan" in s || "hochdach" in s -> MINIVAN
                "kleinwagen" in s || "small car" in s || "city car" in s || "kleinstwagen" in s -> SMALL_CAR
                "limousine" in s || "sedan" in s || "sedán" in s || "hatchback" in s ||
                    "halvkombi" in s || "liftback" in s || "schrägheck" in s -> SEDAN
                else -> OTHER
            }
        }
    }
}

@Serializable
enum class Drivetrain {
    FWD, RWD, AWD;

    companion object {
        fun parse(raw: String?): Drivetrain? {
            val s = raw?.lowercase()?.trim() ?: return null
            return when {
                s.isBlank() -> null
                "all" in s || "allrad" in s || "4wd" in s || "4x4" in s || "awd" in s ||
                    "quattro" in s || "vierhjul" in s || "firehjul" in s || "4motion" in s -> AWD
                "rear" in s || "heck" in s || "baghjul" in s || "baklul" in s -> RWD
                "front" in s || "forhjul" in s || "framhjul" in s || "voorwiel" in s -> FWD
                else -> null
            }
        }
    }
}

@Serializable
enum class VehicleCondition {
    NEW, USED, DEMO, PRE_REGISTRATION, ANNUAL_CAR, CLASSIC, DAMAGED;

    companion object {
        fun parse(raw: String?): VehicleCondition? {
            val s = raw?.lowercase()?.trim() ?: return null
            return when {
                s.isBlank() -> null
                "vorführ" in s || "vorfuehr" in s || "demo" in s || "předváděcí" in s -> DEMO
                "tageszulassung" in s || "pre-reg" in s || "pre reg" in s || "day" in s -> PRE_REGISTRATION
                "jahreswagen" in s || "annual" in s -> ANNUAL_CAR
                "oldtimer" in s || "youngtimer" in s || "classic" in s || "veterán" in s -> CLASSIC
                "unfall" in s || "damaged" in s || "havar" in s || "uszkodz" in s || "defekt" in s -> DAMAGED
                "neu" in s || "new" in s || "nieuw" in s || "nové" in s -> NEW
                "gebraucht" in s || "used" in s || "gebruikt" in s || "ojeté" in s || "begagnad" in s -> USED
                else -> null
            }
        }
    }
}
