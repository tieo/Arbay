package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.model.carCriteria
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.CancellationException
import io.github.tieo.arbay.neuland.NeulandOffers
import org.slf4j.LoggerFactory
import io.github.tieo.arbay.relevance.CarQueryResolver
import io.github.tieo.arbay.relevance.VehicleTextParser

class KleinanzeigenCrawler(
    private val client: HttpClient,
    /** One page of the site's answer, as a car or van with the vehicle's conditions or by the
     *  search's words, under a price floor and ceiling; Neuland's reader of it. */
    private val page: suspend (SearchQuery, NeulandOffers.VehicleQuery?, Int, Money?, Money?) -> NeulandOffers.Answer = { query, vehicle, page, floor, max ->
        NeulandOffers.ask(
            "kleinanzeigen.de", query.positiveText, PlatformId.KLEINANZEIGEN, "Kleinanzeigen", page, floor, max,
            vehicle = vehicle, near = query.area("DE"), otherwise = null,
        )
    },
) : Crawler, StartsAtAnyPage, FetchesEveryPage, FiltersAtTheSource, SuggestsRelatedSearches, KnowsListingAge, KnowsLocation, HasDetailSpecs, KnowsAdState, CopiesAds, ReadsGalleries {
    override val nativeCriteria = setOf(
        FiltersAtTheSource.Criterion.FUEL,
        FiltersAtTheSource.Criterion.YEAR, FiltersAtTheSource.Criterion.MILEAGE,
        FiltersAtTheSource.Criterion.PRICE,
    )

    override val platformId = PlatformId.KLEINANZEIGEN

    private val log = LoggerFactory.getLogger(KleinanzeigenCrawler::class.java)

    override suspend fun search(query: SearchQuery): List<Listing> {
        if (query.freeOnly) {
            val all = mutableListOf<Listing>()
            searchFreeItemsStreaming(query) { _, results, _ -> all.addAll(results) }
            return all
        }
        val car = CarQueryResolver.resolve(query.positiveText)
        // A car is searched in Autos with its make and model, and among the commercial vehicles with
        // its model's word, where van sellers file most of them: 1,390 Crafters between 10,000 and
        // 30,990 € there against 28 in Autos. Each reads its price band cheapest first, up to the
        // band's cap.
        val passes = if (car == null) listOf(Pass(null, byPrice = false))
        // The commercial vehicles are searched by the model's word alone, which for a model that is
        // only a number ("Polestar 2") finds trailer wheels and tractor tyres rather than vans.
        else listOfNotNull(
            Pass(vehicleQuery(query, car), byPrice = true),
            car.modelSlug?.takeIf { m -> m.any(Char::isLetter) }?.let { Pass(NeulandOffers.VehicleQuery(make = car.makeSlug, model = it, vans = true), byPrice = true) },
        )
        val band = if (car != null) query.wholePriceBand() else query
        val all = mutableListOf<Listing>()
        val seen = mutableSetOf<String>()
        for ((index, pass) in passes.withIndex()) readPages(band, pass, all, seen, firstPass = index == 0)
        return all
    }

    /**
     * What Kleinanzeigen's own search is asked of a car: its make and model, and the fuel, year,
     * mileage and gearbox [atTheSource] leaves to the site, whose car ads all state them. The site
     * takes one fuel only, so several are judged here. The power is stated by 1364 of 1503 car ads,
     * and the site's filter drops the other 139 rather than leaving them unjudged, so it is asked
     * for only when the search excludes unstated specs anyway.
     */
    internal fun vehicleQuery(query: SearchQuery, car: CarQueryResolver.CarQuery): NeulandOffers.VehicleQuery {
        val criteria = query.carCriteria.atTheSource()
        return NeulandOffers.VehicleQuery(
            make = car.makeSlug,
            model = car.modelSlug,
            fuels = listOfNotNull(query.carFilters?.fuels?.singleOrNull()?.let(NeulandOffers.VehicleQuery::fuel)),
            gearbox = criteria.transmission?.let { if (it == Transmission.AUTOMATIC) "automatic" else "manual" },
            registeredFrom = criteria.firstRegFromYear,
            registeredTo = criteria.firstRegToYear,
            mileageMinKm = criteria.minMileageKm,
            mileageMaxKm = criteria.maxMileageKm,
            powerMinKw = criteria.minPowerKw.takeIf { criteria.strictUnknown },
            powerMaxKw = criteria.maxPowerKw.takeIf { criteria.strictUnknown },
        )
    }

    /** One way of asking Kleinanzeigen for the search: as a car or van with [vehicle]'s conditions
     *  or by its words; [byPrice] when its answer comes cheapest first. */
    private class Pass(val vehicle: NeulandOffers.VehicleQuery?, val byPrice: Boolean)

    /** Reads [query]'s pages from [pass] into [allResults], until the pages run out or the offers wanted are in. */
    private suspend fun readPages(
        query: SearchQuery,
        pass: Pass,
        allResults: MutableList<Listing>,
        seenIds: MutableSet<String>,
        firstPass: Boolean,
    ) {
        val start = allResults.size
        var floor = query.minPrice
        var firstPage = query.startPage
        var firstFetch = true
        while (true) {
            // The dearest offer on the last page read when the pages ended while still full.
            var lastFullPageTop: Money? = null
            val endPage = firstPage + query.pageLimit() - 1
            for (page in firstPage..endPage) {
                val answer = try {
                    page(query, pass.vehicle, page, floor, query.maxPrice)
                } catch (e: CrawlerBlockedException) {
                    if (firstFetch && firstPass) throw e
                    return
                }
                val pageResults = answer.listings
                if (pageResults.isEmpty()) return
                if (firstFetch && firstPass) emitSuggestedTerms(answer.related)
                firstFetch = false
                val newResults = pageResults.filter { seenIds.add(it.externalId) }
                allResults.addAll(newResults)
                if (newResults.isNotEmpty()) emitPartialResults(newResults)

                // A later page holding nothing the earlier ones did not is the site ignoring the page
                // number; asking for the next one would fetch the same page again.
                if (page > firstPage && newResults.isEmpty()) {
                    lastFullPageTop = pageResults.maxBy { it.price.amount }.price
                    break
                }
                if (pageResults.size < 10) return
                if (allResults.size - start >= query.resultCap()) return
                if (page == endPage) lastFullPageTop = pageResults.maxBy { it.price.amount }.price
            }
            // Kleinanzeigen serves 50 pages of one answer and then the 50th again: 1,390 Crafters
            // between 10,000 and 30,990 € in Nutzfahrzeuge ended at 27,999 €. An answer cheapest
            // first goes on as a new answer from the last price read; the offers at that price are
            // read again and counted once.
            val next = lastFullPageTop?.takeIf { pass.byPrice && (floor == null || it.amount > floor.amount) } ?: return
            floor = next
            firstPage = 1
        }
    }

    /**
     * Things given away ("Zu verschenken") around the place [query] names, page by page into
     * [onPageResults]; lost pets, filed there too, left out. Answers how many were read and whether
     * pages are left.
     */
    suspend fun searchFreeItemsStreaming(
        query: SearchQuery,
        onPageResults: suspend (page: Int, results: List<Listing>, totalSoFar: Int) -> Unit,
    ): Pair<Int, Boolean> {
        val seenIds = mutableSetOf<String>()
        val endPage = query.startPage + (query.maxPages ?: CrawlerConfig.current.maxPages) - 1
        var total = 0
        var hasMore = false
        for (page in query.startPage..endPage) {
            val pageResults = try {
                NeulandOffers.ask(
                    "kleinanzeigen.de", "", platformId, "Kleinanzeigen", page, max = Money.cents(0),
                    near = query.area("DE"),
                ).listings.filter { kindOf(it.url) != LOST_PETS }
            } catch (e: CrawlerBlockedException) {
                if (page == query.startPage) throw e
                break
            }
            if (pageResults.isEmpty()) break
            val newResults = pageResults.filter { seenIds.add(it.externalId) }
            total += newResults.size
            log.info("Free items page {}: {} free, {} new, total={}", page, pageResults.size, newResults.size, total)
            if (newResults.isNotEmpty()) onPageResults(page, newResults, total)
            if (pageResults.size < 3) break
            hasMore = page < endPage
        }
        return total to hasMore
    }

    /** The category an ad's address names in its last part ("<ad>-<category>-<place>"). */
    private fun kindOf(url: String) = url.substringAfterLast('/').split('-').getOrNull(1)

    /** The ad's own page, read by Neuland: the details the card leaves out, the seller's whole text
     *  (where a van's wheelbase is written when it is written at all), the shipping, the gallery and
     *  the seller's box. */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        val page = NeulandOffers.detail(listing.url, "Kleinanzeigen")
        // The wheelbase is never one of the detail rows: where a seller states it, it is a line of
        // their own prose ("Radstand: 3.250 mm"), so it is read off the text and stays inferred.
        val wheelbase = page.description?.let { VehicleTextParser.parse(it)?.wheelbaseMm }
        ListingDetail(
            vehicle = page.vehicle?.copy(wheelbaseMm = wheelbase) ?: wheelbase?.let { VehicleInfo(wheelbaseMm = it) },
            description = page.description,
            shipping = page.shipping,
            photos = page.photos,
            seller = page.seller,
        ).takeIf { it.vehicle != null || it.description != null || it.shipping != null || it.photos.isNotEmpty() }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        log.debug("detail fetch failed for {}: {}", listing.url, e.message?.take(60))
        null
    }

    override suspend fun adNow(url: String): AdNow? = try {
        val page = NeulandOffers.detail(url, "Kleinanzeigen")
        AdNow(state = page.state(), price = page.price, shipping = page.shipping).takeIf { it.state != null || it.price != null }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        log.debug("ad unreadable for {}: {}", url, e.message?.take(60))
        null
    }

    /** The ad whole, for selling one like it: its title, the seller's text, its category, its
     *  details and the gallery's photos in their order. */
    override suspend fun adCopy(url: String): AdCopy? = try {
        val page = NeulandOffers.detail(url, "Kleinanzeigen")
        val title = page.title
        val category = page.kind
        if (title == null || category == null) null
        else AdCopy(title, page.description.orEmpty(), category, page.specs.toMap(), page.photos)
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        log.debug("ad unreadable for {}: {}", url, e.message?.take(60))
        null
    }

    /** The photo at 1200 by 1600, the largest rule the image server answers ($_57). */
    override suspend fun photo(url: String): ByteArray {
        val response = client.get("${url.substringBefore('?')}?rule=\$_57.JPG")
        check(response.status.value == 200) { "photo answered ${response.status.value}" }
        return response.bodyAsBytes()
    }

    override suspend fun adState(url: String): AdState? = try {
        NeulandOffers.detail(url, "Kleinanzeigen").state()
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        log.debug("ad state unreadable for {}: {}", url, e.message?.take(60))
        null
    }

    /** Deleted, paused or up, as the page's own settings say; a reserved ad is not told apart from
     *  a live one, the page having no setting of its own for it. */
    private fun NeulandOffers.Detail.state(): AdState? = when {
        available == false -> AdState.DELETED
        paused -> AdState.PAUSED
        available == true -> AdState.LIVE
        else -> null
    }

    private companion object {
        /** "Vermisste Tiere": lost pet notices, filed among the free things, not things to pick up. */
        const val LOST_PETS = "283"
    }
}
