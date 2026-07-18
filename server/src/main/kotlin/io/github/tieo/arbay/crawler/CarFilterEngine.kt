package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VehicleField
import io.github.tieo.arbay.model.VehicleInfo

/**
 * Enforces car filters against the parsed VehicleInfo, so a constraint a marketplace can't
 * apply server-side (mileage on Kleinanzeigen, anything on the query-only crawlers) is still
 * honored from our own data. A listing is dropped only when it has the relevant value and it
 * fails; a missing value is a soft pass (unknown != excluded), matching the "note it, don't
 * silently hide" rule — the coverage note for unverified filters is layered on separately.
 *
 * Also drops obvious non-vehicles (parts/accessories) that leak from general classifieds on a
 * car query: no year, mileage, power or displacement and a price too low to be a running car.
 */
object CarFilterEngine {

    /** Sites that carry non-car inventory, where a car query can return parts/accessories. */
    private val GENERAL_PLATFORMS = setOf(
        PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE, PlatformId.MARKTPLAATS, PlatformId.WILLHABEN,
    )

    fun apply(listings: List<Listing>, filters: CarFilters): List<Listing> =
        listings.map { annotateVanDims(it) }.filter { keep(it, filters) }

    /** For each active filter dimension, how many more of [candidates] would pass if that one
     *  filter were dropped (all others kept) — the "−N" a chip is hiding. Computed locally over
     *  the fetched candidate set; only card/text-derived specs are known, so it is an estimate
     *  for detail-only fields (fuel/gearbox), exact for price/year/mileage/power/van size. */
    fun facetCounts(candidates: List<Listing>, filters: CarFilters): Map<String, Int> {
        if (filters.isEmpty) return emptyMap()
        val base = apply(candidates, filters).size
        val out = mutableMapOf<String, Int>()
        fun probe(key: String, relaxed: CarFilters) {
            if (relaxed != filters) out[key] = apply(candidates, relaxed).size - base
        }
        probe("price", filters.copy(minPriceEur = null, maxPriceEur = null))
        probe("year", filters.copy(firstRegFromYear = null, firstRegToYear = null))
        probe("mileage", filters.copy(minMileageKm = null, maxMileageKm = null))
        probe("power", filters.copy(minPowerKw = null, maxPowerKw = null))
        probe("transmission", filters.copy(transmission = null))
        probe("fuel", filters.copy(fuels = emptySet()))
        probe("bodyType", filters.copy(bodyTypes = emptySet()))
        probe("condition", filters.copy(conditions = emptySet()))
        probe("color", filters.copy(colors = emptySet()))
        probe("drivetrain", filters.copy(drivetrain = null))
        probe("doors", filters.copy(minDoors = null))
        probe("seats", filters.copy(minSeats = null))
        probe("emission", filters.copy(minEmissionEuro = null))
        probe("seller", filters.copy(sellerType = null))
        probe("vanLength", filters.copy(vanLengths = emptySet()))
        probe("vanHeight", filters.copy(vanHeights = emptySet()))
        probe("description", filters.copy(descriptionContains = null))
        return out
    }

    /** Fill the van size classes from the listing text. A size the listing STATES — an explicit
     *  code ("L3H2") or a wheelbase/roof word ("Maxi", "lang", "Hochdach") — is a known value that
     *  the filter may exclude on: filtering L3 must drop a van that says "Maxi" (L4). Only a van
     *  that states nothing is soft-passed. */
    private fun annotateVanDims(listing: Listing): Listing {
        val text = "${listing.title} ${listing.description ?: ""}"
        val inferred = VanDimensions.inferred(text)
        if (inferred.length == null && inferred.height == null) return listing
        val v = listing.vehicle ?: VehicleInfo()
        val verified = v.verified.toMutableSet()
        if (inferred.length != null) verified += VehicleField.VAN_LENGTH
        if (inferred.height != null) verified += VehicleField.VAN_HEIGHT
        return listing.copy(
            vehicle = v.copy(vanLength = inferred.length, vanHeight = inferred.height, verified = verified),
        )
    }

    private fun keep(listing: Listing, filters: CarFilters): Boolean {
        val v = listing.vehicle

        if (isLikelyNonVehicle(listing)) return false

        // Find-in-description: every whitespace-separated term must appear in the title or
        // description. This is a literal match on text we hold, so excluding is safe.
        filters.descriptionContains?.takeIf { it.isNotBlank() }?.let { needle ->
            val haystack = "${listing.title} ${listing.description ?: ""}".lowercase()
            if (!needle.lowercase().split(Regex("\\s+")).all { it.isBlank() || haystack.contains(it) })
                return false
        }

        // Price is always known (it's on the listing), so it can always exclude.
        val priceEur = priceEurCents(listing) / 100
        filters.minPriceEur?.let { if (priceEur < it) return false }
        filters.maxPriceEur?.let { if (priceEur > it) return false }

        // Seller type comes from the listing, not VehicleInfo.
        filters.sellerType?.let { want -> listing.seller?.type?.let { if (it != want) return false } }

        if (v != null) {
            // Exclude ONLY on verified fields (from the site's structured data). A text-inferred
            // value that's wrong must never drop a listing that actually fits — false exclusion
            // loses a real deal, which is worse than a soft-pass we can badge as unverified.
            if (v.isVerified(VehicleField.FIRST_REG_YEAR)) v.firstRegYear?.let {
                filters.firstRegFromYear?.let { min -> if (it < min) return false }
                filters.firstRegToYear?.let { max -> if (it > max) return false }
            }
            if (v.isVerified(VehicleField.MILEAGE)) v.mileageKm?.let {
                filters.minMileageKm?.let { min -> if (it < min) return false }
                filters.maxMileageKm?.let { max -> if (it > max) return false }
            }
            if (v.isVerified(VehicleField.POWER)) v.powerKw?.let {
                filters.minPowerKw?.let { min -> if (it < min) return false }
                filters.maxPowerKw?.let { max -> if (it > max) return false }
            }
            if (v.isVerified(VehicleField.GEARBOX)) v.gearbox?.let {
                filters.transmission?.let { want -> if (it != want) return false }
            }
            if (filters.fuels.isNotEmpty() && v.isVerified(VehicleField.FUEL))
                v.fuel?.let { if (it !in filters.fuels) return false }
            if (filters.bodyTypes.isNotEmpty() && v.isVerified(VehicleField.BODY_TYPE))
                v.bodyType?.let { if (it !in filters.bodyTypes) return false }
            if (filters.conditions.isNotEmpty() && v.isVerified(VehicleField.CONDITION))
                v.condition?.let { if (it !in filters.conditions) return false }
            filters.drivetrain?.let { want -> if (v.isVerified(VehicleField.DRIVETRAIN)) v.drivetrain?.let { if (it != want) return false } }
            filters.minDoors?.let { min -> if (v.isVerified(VehicleField.DOORS)) v.doors?.let { if (it < min) return false } }
            filters.minSeats?.let { min -> if (v.isVerified(VehicleField.SEATS)) v.seats?.let { if (it < min) return false } }
            filters.minEmissionEuro?.let { min -> if (v.isVerified(VehicleField.EMISSION)) v.emissionClassEuro?.let { if (it < min) return false } }
            if (filters.colors.isNotEmpty() && v.isVerified(VehicleField.COLOR))
                v.color?.let { c -> if (filters.colors.none { c.contains(it, ignoreCase = true) }) return false }
            if (filters.vanLengths.isNotEmpty() && v.isVerified(VehicleField.VAN_LENGTH))
                v.vanLength?.let { if (it !in filters.vanLengths) return false }
            if (filters.vanHeights.isNotEmpty() && v.isVerified(VehicleField.VAN_HEIGHT))
                v.vanHeight?.let { if (it !in filters.vanHeights) return false }
        }
        return true
    }

    private fun priceEurCents(listing: Listing): Long =
        if (listing.price.currency == Currency.EUR) listing.price.amount
        else ExchangeRates.convert(listing.price.amount, listing.price.currency.name, "EUR")

    // Wanted-ad openers on the German classifieds: someone looking to BUY, not a car for sale.
    private val wantedAd = Regex("""^\s*(suche|suchen|gesucht|kaufe|ankauf|ankaufe|biete geld)\b""", RegexOption.IGNORE_CASE)

    // Rental/hire ads: a van offered to rent, not to buy. These carry real specs (mileage,
    // power), so they must be caught before the spec-signal exemption below.
    private val rentalAd = Regex("""\b(mieten|zu mieten|vermiet\w+|mietwagen|leihwagen|autovermietung|langzeitmiete|tagesmiete)\b""", RegexOption.IGNORE_CASE)

    // A part or accessory, never a whole vehicle. Only nouns that a car-for-sale title would not
    // lead with — deliberately NOT the "recently replaced" parts a seller brags about (Zahnriemen,
    // Kupplung, Bremsscheibe, Turbolader), which appear in genuine car titles and would false-drop.
    private val partAccessory = Regex(
        """\b(dreh|sitz|mittel)?konsole\b|\bhalterung\b|\bsitzbez(ug|üge|uege)\b|\bfu(ß|ss)matten\b|""" +
            """\bgummimatten\b|\bkofferraumwanne\b|\bdach(gepäck)?träger\b|\bradkappen?\b|\babdeckplane\b|""" +
            """\bwindschott\b|\bspiegelglas\b|\bscheinwerfer\b|\brück(leuchte|licht)\b|\bkotflügel\b|""" +
            """\bzierleiste\b|\bschriftzug\b|\bersatzteile?\b|\bsteuergerät\b|""" +
            """\b(bracket|floor ?mat|seat ?cover|headlight|tail ?light|fender|mudflap|wheel ?trim|badge)\b|""" +
            // Dutch (Marktplaats) — unambiguous car-part nouns, never a whole-vehicle listing.
            """\b(koplamp|achterlicht|spatbord|onderdeel|onderdelen|dakdrager|portier|motorkap|spiegelkap|stoelhoezen?)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** A car-query result that is not a car for sale: a wanted ad, a rental, or a part/accessory.
     *  All matched on the title only (safe, explicit). The part guard is needed because eBay's
     *  price-ascending sort floats up cheap seller-miscategorised parts that sit inside the vehicle
     *  category (a "Drehkonsole" listed under Fahrzeuge) — the source category alone does not stop
     *  them. Any listing the site gave structured vehicle specs for (verified mileage / first-reg /
     *  power) is exempt, so a real car is never dropped. No price threshold: a cheap or broken car
     *  is still a car. */
    private fun isLikelyNonVehicle(listing: Listing): Boolean {
        if (listing.platformId !in GENERAL_PLATFORMS) return false
        if (wantedAd.containsMatchIn(listing.title)) return true
        if (rentalAd.containsMatchIn(listing.title)) return true
        val hasVehicleSpec = listing.vehicle?.let { v ->
            v.isVerified(VehicleField.MILEAGE) || v.isVerified(VehicleField.FIRST_REG_YEAR) ||
                v.isVerified(VehicleField.POWER)
        } ?: false
        if (!hasVehicleSpec && partAccessory.containsMatchIn(listing.title)) return true
        return false
    }
}
