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

    // A watch run announces what it keeps and nobody waits on it, so it reads the page of every
    // listing it would announce unread. Read newest first under the 25-page cap, the Crafter watch
    // announced five Kleinanzeigen vans found that run but listed 37 to 125 days before as new with
    // their gearbox unknown, and every one of their pages says "Schaltgetriebe". This bounds only a
    // runaway: a run reads the pages it has not read before, a few dozen a run once a search has run.
    private const val MAX_FETCHES_UNATTENDED = 150

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

    /** Enrich one platform's listings in place of the crawler that produced them. An [unattended]
     *  watch run reads every unread page, newest first, each one waiting for its own request window. */
    suspend fun enrich(listings: List<Listing>, filters: CarFilters, crawler: Crawler, unattended: Boolean = false): List<Listing> {
        val needed = neededFields(filters)
        if (needed.isEmpty()) return listings

        // Spend the fetch budget on the listings most likely to be shown — cheapest first —
        // so the results the user actually sees get verified, not whatever came first in the
        // crawler's order. Cached details are free and always applied; the rest fill in on a
        // re-run from cache.
        // A watch run reads every unread page instead ([MAX_FETCHES_UNATTENDED]), newest first so
        // only a runaway's oldest ads go unread: what it finds new is announced and may be pushed.
        // Cheapest first, the Crafter watch announced Kleinanzeigen vans of 27,000 € and more whose
        // cards say nothing of their gearbox as new with it unknown, their pages left behind the
        // cheaper unread ones, and five of the pages say "Schaltgetriebe".
        val unread = listings.filter { needsDetail(it, needed) && DetailCache.get(it.id) == null || galleryUnread(it, crawler) }
        val toFetch = (if (unattended) unread.sortedByDescending { it.listingDate } else unread.sortedBy { priceEurCents(it) })
            .take(if (unattended) MAX_FETCHES_UNATTENDED else MAX_FETCHES_PER_PLATFORM)
            .mapTo(HashSet()) { it.id }

        if (crawler is ReadsAdsTogether && toFetch.isNotEmpty()) {
            crawler.fetchDetails(listings.filter { it.id in toFetch })
                .forEach { (id, detail) -> DetailCache.put(id, detail) }
        }

        val started = kotlin.time.Clock.System.now().toEpochMilliseconds()
        return listings.map { listing ->
            // A person waiting on the search gives all its pages one wait; a watch run gives each page its own.
            val deadline = (if (unattended) kotlin.time.Clock.System.now().toEpochMilliseconds() else started) + MAX_WAIT_FOR_BUDGET_MS
            val fetch = listing.id in toFetch && crawler !is ReadsAdsTogether &&
                RequestMonitor.awaitBudget(crawler.platformId.displayName, deadline)
            // A page read is the market answering, so the crawl's clock starts over: a watch run
            // reading a hundred pages one after another outlasts the market's time limit otherwise.
            val detail = if (fetch)
                gate.withPermit { crawler.fetchDetail(listing) }
                    ?.also { DetailCache.put(listing.id, it); kotlin.coroutines.coroutineContext[CrawlClock]?.answered() }
                    ?: DetailCache.get(listing.id)
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
