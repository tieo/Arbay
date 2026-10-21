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
    // How many of a market's listings one search may open. Measured on AutoScout24: 15 left 35 of
    // 50 vans unchecked against a wheelbase, which is a filter that hardly narrows anything.
    // Each of these is one page on a market that answers a plain client, three at a time.
    private const val MAX_FETCHES_PER_PLATFORM = 25

    // How long one market's pages may wait for its request window ([RequestMonitor]): a search
    // reading a whole price band spends the window on its result pages, and a page read refused
    // there left every Kleinanzeigen van of a watch run unread. Half the market's crawl budget,
    // so the wait never costs the market its whole answer.
    private const val MAX_WAIT_FOR_BUDGET_MS = 90_000L
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
        if (filters.minWheelbaseMm != null || filters.maxWheelbaseMm != null) add(VehicleField.WHEELBASE)
        // A van's size is read off its text and wheelbase, which the page has and the card often
        // lacks: a mobile.de card says only "Volkswagen Crafter".
        if (filters.vanLengths.isNotEmpty()) add(VehicleField.VAN_LENGTH)
        if (filters.vanHeights.isNotEmpty()) add(VehicleField.VAN_HEIGHT)
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
            .filter { needsDetail(it, needed) && DetailCache.get(it.id) == null || galleryUnread(it, crawler) }
            .sortedBy { priceEurCents(it) }
            .take(MAX_FETCHES_PER_PLATFORM)
            .mapTo(HashSet()) { it.id }

        if (crawler is ReadsAdsTogether && toFetch.isNotEmpty()) {
            crawler.fetchDetails(listings.filter { it.id in toFetch })
                .forEach { (id, detail) -> DetailCache.put(id, detail) }
        }

        val deadline = kotlin.time.Clock.System.now().toEpochMilliseconds() + MAX_WAIT_FOR_BUDGET_MS
        return listings.map { listing ->
            val fetch = listing.id in toFetch && crawler !is ReadsAdsTogether &&
                RequestMonitor.awaitBudget(crawler.platformId.displayName, deadline)
            val detail = if (fetch)
                gate.withPermit { crawler.fetchDetail(listing) }?.also { DetailCache.put(listing.id, it) } ?: DetailCache.get(listing.id)
            else DetailCache.get(listing.id)
            // Beyond the budget or unread, a listing stays inferred and badged unverified.
            val read = if (detail != null && needsDetail(listing, needed)) listing.withDetail(detail) else listing
            read.also { printGallery(it, detail, crawler) }
        }
    }

    /** [listing] with the page read for it before laid over its card, where one was: free, so a
     *  search's first answer already knows what an earlier read found (a 2015 van with 375,000 km
     *  ranked at 74 % on its bare card while the search was still reading pages). */
    fun withCachedDetail(listing: Listing): Listing =
        DetailCache.get(listing.id)?.let { listing.withDetail(it) } ?: listing

    /** A listing whose page holds a gallery that was never read: read before galleries were, or replaced since. */
    private fun galleryUnread(listing: Listing, crawler: Crawler): Boolean =
        crawler is ReadsGalleries && DetailCache.get(listing.id)?.photos.isNullOrEmpty()

    /**
     * The whole gallery of an ad whose page has been read, printed so its photos can be found in
     * other ads. A gallery of which no photo can be fetched any more was replaced by the seller,
     * so the page is forgotten and read again.
     */
    private suspend fun printGallery(listing: Listing, detail: io.github.tieo.arbay.model.ListingDetail?, crawler: Crawler) {
        val photos = detail?.photos?.takeIf { it.isNotEmpty() } ?: return
        val printed = runCatching { PhotoPrints.record(listing, photos) }.getOrDefault(true)
        if (!printed && crawler is ReadsGalleries) DetailCache.remove(listing.id)
    }

    /** The page's answers laid over the card's: its specs win where it has one, its own text
     *  replaces the site's one-line summary, and a location it states fills in a missing one.
     *  The full text is read again for what the fields leave out: a Kleinanzeigen commercial
     *  vehicle's page has no field but its type, and states its year, mileage and gearbox only
     *  in the seller's words. Those words outrank the guess made from the card's summary: a
     *  Nienburg Crafter's page says "Getriebe: Schaltgetriebe", and the card's "Start-Stop-
     *  Automatik" had made it an automatic. */
    internal fun Listing.withDetail(detail: io.github.tieo.arbay.model.ListingDetail): Listing = VehicleTextParser.enrich(copy(
        vehicle = VehicleTextParser.merge(
            VehicleTextParser.merge(
                detail.vehicle?.withWheelbaseReadAgain(title, detail.description),
                VehicleTextParser.merge(
                    VehicleTextParser.verifiedOnly(vehicle),
                    detail.description?.let { VehicleTextParser.parse("$title $it") },
                ),
            ),
            vehicle,
        ),
        description = detail.description?.takeIf { it.length > (description?.length ?: 0) } ?: description,
        location = location ?: detail.location,
        listingDate = listingDate ?: detail.listedAt,
        seller = detail.sellerReviews?.let { reviews -> seller?.copy(reviewCount = reviews) } ?: seller,
    ))

    /**
     * The wheelbase a page states, read again from the page's text the cache keeps. No market has
     * a field for it, so it was only ever read off the seller's prose, and a page cached under an
     * older reading kept that reading for the cache's thirty days: a Kleinanzeigen Crafter titled
     * "mittlerer Radstand 2.0 TDI" still showed a 2000 mm wheelbase nine hours after the reading
     * was fixed. Where the page left no text behind, what it was read as stays.
     */
    private fun io.github.tieo.arbay.model.VehicleInfo.withWheelbaseReadAgain(title: String, text: String?): io.github.tieo.arbay.model.VehicleInfo {
        if (text == null) return this
        val read = VehicleTextParser.parseWheelbaseMm("$title\n$text")
        return copy(wheelbaseMm = read, verified = if (read == null) verified - VehicleField.WHEELBASE else verified)
    }

    private fun priceEurCents(listing: Listing): Long =
        if (listing.price.currency == io.github.tieo.arbay.model.Currency.EUR) listing.price.amount
        else ExchangeRates.convert(listing.price.amount, listing.price.currency.name, "EUR")
}
