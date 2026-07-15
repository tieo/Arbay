package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VehicleField

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
        listings.filter { keep(it, filters) }

    /** Per-filter marginal "removed" counts: for each active filter, how many listings pass
     *  every other filter but fail this one — i.e. how many extra results relaxing it would add.
     *  Keyed by a stable chip label ("year"/"mileage"/"power"/"gearbox"). Additive across
     *  platforms, so the app sums per-platform contributions. */
    fun facetRemoved(listings: List<Listing>, filters: CarFilters): Map<String, Int> {
        val keys = buildList {
            if (filters.firstRegFromYear != null || filters.firstRegToYear != null) add("year")
            if (filters.maxMileageKm != null) add("mileage")
            if (filters.minPowerKw != null) add("power")
            if (filters.transmission != null) add("gearbox")
        }
        return keys.associateWith { key ->
            listings.count { l ->
                passesDescription(l, filters) && !isLikelyNonVehicle(l) &&
                    keys.filter { it != key }.all { passesField(l, filters, it) } &&
                    !passesField(l, filters, key)
            }
        }.filterValues { it > 0 }
    }

    private fun keep(listing: Listing, filters: CarFilters): Boolean {
        if (isLikelyNonVehicle(listing)) return false
        if (!passesDescription(listing, filters)) return false
        return listOf("year", "mileage", "power", "gearbox").all { passesField(listing, filters, it) }
    }

    /** Find-in-description: every whitespace-separated term must appear in title or description.
     *  A literal match on text we hold, so excluding is safe. */
    private fun passesDescription(listing: Listing, filters: CarFilters): Boolean {
        val needle = filters.descriptionContains?.takeIf { it.isNotBlank() } ?: return true
        val haystack = "${listing.title} ${listing.description ?: ""}".lowercase()
        return needle.lowercase().split(Regex("\\s+")).all { it.isBlank() || haystack.contains(it) }
    }

    /** True if the listing passes one filter dimension. Excludes ONLY on verified fields — a
     *  text-inferred value that's wrong must never drop a listing that actually fits. */
    private fun passesField(listing: Listing, filters: CarFilters, key: String): Boolean {
        val v = listing.vehicle ?: return true
        return when (key) {
            "year" -> {
                val y = v.firstRegYear
                if (v.isVerified(VehicleField.FIRST_REG_YEAR) && y != null) {
                    val from = filters.firstRegFromYear
                    val to = filters.firstRegToYear
                    (from == null || y >= from) && (to == null || y <= to)
                } else true
            }
            "mileage" -> {
                val km = v.mileageKm
                val max = filters.maxMileageKm
                if (v.isVerified(VehicleField.MILEAGE) && km != null && max != null) km <= max else true
            }
            "power" -> {
                val kw = v.powerKw
                val min = filters.minPowerKw
                if (v.isVerified(VehicleField.POWER) && kw != null && min != null) kw >= min else true
            }
            "gearbox" -> {
                val g = v.gearbox
                val want = filters.transmission
                if (v.isVerified(VehicleField.GEARBOX) && g != null && want != null) g == want else true
            }
            else -> true
        }
    }

    /** A car-query result on a general marketplace with no vehicle signal and a throwaway price
     *  is a part or accessory, not a car. Car-only platforms are exempt (every result is a car). */
    private fun isLikelyNonVehicle(listing: Listing): Boolean {
        if (listing.platformId !in GENERAL_PLATFORMS) return false
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
