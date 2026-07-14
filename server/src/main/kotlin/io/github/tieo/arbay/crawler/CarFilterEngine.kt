package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Transmission

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

    private fun keep(listing: Listing, filters: CarFilters): Boolean {
        val v = listing.vehicle

        if (isLikelyNonVehicle(listing)) return false

        if (v != null) {
            filters.firstRegFromYear?.let { min -> v.firstRegYear?.let { if (it < min) return false } }
            filters.firstRegToYear?.let { max -> v.firstRegYear?.let { if (it > max) return false } }
            filters.maxMileageKm?.let { max -> v.mileageKm?.let { if (it > max) return false } }
            filters.minPowerKw?.let { min -> v.powerKw?.let { if (it < min) return false } }
            filters.transmission?.let { want -> v.gearbox?.let { if (it != want) return false } }
        }
        return true
    }

    /** A car-query result on a general marketplace with no vehicle signal and a throwaway price
     *  is a part or accessory, not a car. Car-only platforms are exempt (every result is a car). */
    private fun isLikelyNonVehicle(listing: Listing): Boolean {
        if (listing.platformId !in GENERAL_PLATFORMS) return false
        val v = listing.vehicle
        val hasSignal = v != null &&
            (v.firstRegYear != null || v.mileageKm != null || v.powerKw != null || v.displacementCc != null)
        if (hasSignal) return false
        val eurCents = if (listing.price.currency == Currency.EUR) listing.price.amount
        else ExchangeRates.convert(listing.price.amount, listing.price.currency.name, "EUR")
        return eurCents < JUNK_PRICE_CEILING_CENTS
    }
}
