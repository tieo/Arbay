package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.VehicleField
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Promotes a platform's listings from inferred to verified specs by fetching detail pages,
 * so a filter on power/gearbox/fuel actually enforces instead of soft-passing. Block-safe by
 * construction: only fetches listings that (a) survived the card-level filters and (b) still
 * lack a verified value for a field the active filters use; caps the number of fetches per
 * search; caches each detail permanently (DetailCache); and bounds concurrency. When no active
 * filter needs an unverified field, it fetches nothing.
 */
object DetailEnricher {
    private const val MAX_FETCHES_PER_PLATFORM = 15
    private val gate = Semaphore(3)

    /** Fields the given filters constrain and that a detail page could verify. */
    private fun neededFields(filters: CarFilters): Set<VehicleField> = buildSet {
        if (filters.minPowerKw != null || filters.maxPowerKw != null) add(VehicleField.POWER)
        if (filters.transmission != null) add(VehicleField.GEARBOX)
        if (filters.firstRegFromYear != null || filters.firstRegToYear != null) add(VehicleField.FIRST_REG_YEAR)
        if (filters.minMileageKm != null || filters.maxMileageKm != null) add(VehicleField.MILEAGE)
        if (filters.fuels.isNotEmpty()) add(VehicleField.FUEL)
        if (filters.bodyTypes.isNotEmpty()) add(VehicleField.BODY_TYPE)
        if (filters.conditions.isNotEmpty()) add(VehicleField.CONDITION)
        if (filters.colors.isNotEmpty()) add(VehicleField.COLOR)
        if (filters.drivetrain != null) add(VehicleField.DRIVETRAIN)
        if (filters.minDoors != null) add(VehicleField.DOORS)
        if (filters.minSeats != null) add(VehicleField.SEATS)
        if (filters.minEmissionEuro != null) add(VehicleField.EMISSION)
    }

    private fun needsDetail(listing: Listing, needed: Set<VehicleField>): Boolean {
        val v = listing.vehicle
        return needed.any { field -> v == null || !v.isVerified(field) }
    }

    /** Enrich one platform's listings in place of the crawler that produced them. */
    suspend fun enrich(listings: List<Listing>, filters: CarFilters, crawler: Crawler): List<Listing> {
        val needed = neededFields(filters)
        if (needed.isEmpty()) return listings

        // Spend the fetch budget on the listings most likely to be shown — cheapest first —
        // so the results the user actually sees get verified, not whatever came first in the
        // crawler's order. Cached details are free and always applied; the rest fill in on a
        // re-run from cache.
        val toFetch = listings
            .filter { needsDetail(it, needed) && DetailCache.get(it.id) == null }
            .sortedBy { priceEurCents(it) }
            .take(MAX_FETCHES_PER_PLATFORM)
            .mapTo(HashSet()) { it.id }

        return listings.map { listing ->
            if (!needsDetail(listing, needed)) return@map listing
            DetailCache.get(listing.id)?.let { cached ->
                return@map listing.copy(vehicle = VehicleTextParser.merge(cached, listing.vehicle))
            }
            if (listing.id !in toFetch) return@map listing // beyond budget → stays inferred, badged unverified
            val detail = gate.withPermit { crawler.fetchDetailVehicle(listing) } ?: return@map listing
            DetailCache.put(listing.id, detail)
            listing.copy(vehicle = VehicleTextParser.merge(detail, listing.vehicle))
        }
    }

    private fun priceEurCents(listing: Listing): Long =
        if (listing.price.currency == io.github.tieo.arbay.model.Currency.EUR) listing.price.amount
        else ExchangeRates.convert(listing.price.amount, listing.price.currency.name, "EUR")
}
