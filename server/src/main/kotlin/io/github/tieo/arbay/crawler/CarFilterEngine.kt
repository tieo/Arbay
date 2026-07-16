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
    private val GENERAL_PLATFORMS = setOf(PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE, PlatformId.MARKTPLAATS)

    private const val JUNK_PRICE_CEILING_CENTS = 100_000L // €1000

    fun apply(listings: List<Listing>, filters: CarFilters): List<Listing> =
        listings.map { annotateVanDims(it) }.filter { keep(it, filters) }

    /** Fill the display van size classes from the listing text: explicit L/H codes are verified
     *  (may exclude), word inferences ("Hochdach", "Maxi") fill gaps for display only. */
    private fun annotateVanDims(listing: Listing): Listing {
        val text = "${listing.title} ${listing.description ?: ""}"
        val inferred = VanDimensions.inferred(text)
        if (inferred.length == null && inferred.height == null) return listing
        val codes = VanDimensions.excludable(text)
        val v = listing.vehicle ?: VehicleInfo()
        val verified = v.verified.toMutableSet()
        if (codes.length != null) verified += VehicleField.VAN_LENGTH
        if (codes.height != null) verified += VehicleField.VAN_HEIGHT
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

    /** A car-query result on a general marketplace that is a part/accessory or a wanted ad,
     *  not a car for sale. Car-only platforms are exempt (every result is a car). */
    private fun isLikelyNonVehicle(listing: Listing): Boolean {
        if (listing.platformId !in GENERAL_PLATFORMS) return false
        if (wantedAd.containsMatchIn(listing.title)) return true
        val v = listing.vehicle
        // A bare year is too weak — parts titles ("... MAN TGE 2023") carry one. Require an
        // odometer, power or displacement figure, which parts listings don't have.
        val hasSignal = v != null &&
            (v.mileageKm != null || v.powerKw != null || v.displacementCc != null)
        if (hasSignal) return false
        val eurCents = if (listing.price.currency == Currency.EUR) listing.price.amount
        else ExchangeRates.convert(listing.price.amount, listing.price.currency.name, "EUR")
        return eurCents < JUNK_PRICE_CEILING_CENTS
    }
}
