package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.ListingDetail
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class DetailEnricherTest {
    private class Gallery(val photos: List<String>) : Crawler, ReadsGalleries {
        override val platformId = PlatformId.KLEINANZEIGEN
        val read = mutableListOf<String>()
        override suspend fun search(query: SearchQuery) = emptyList<Listing>()
        override suspend fun fetchDetail(listing: Listing): ListingDetail { read += listing.id; return ListingDetail(description = "Crafter", photos = photos) }
    }

    private fun van(id: String) = Listing(
        id = "KLEINANZEIGEN:$id", platformId = PlatformId.KLEINANZEIGEN, externalId = id, url = "https://example.invalid/$id",
        title = "VW Crafter", price = Money(20_000_00, Currency.EUR), scrapedAt = Clock.System.now(),
    )

    @Test
    fun `a page read before its gallery was is read again, and one whose photos are all gone is forgotten`() = runBlocking {
        val stamp = System.nanoTime()
        val galleryLess = van("old-$stamp"); val withGallery = van("new-$stamp")
        DetailCache.put(galleryLess.id, ListingDetail(description = "Crafter"))
        DetailCache.put(withGallery.id, ListingDetail(description = "Crafter", photos = listOf("https://example.invalid/a.jpg")))
        val market = Gallery(listOf("https://example.invalid/b.jpg"))
        DetailEnricher.enrich(listOf(galleryLess, withGallery), CarFilters(maxMileageKm = 150_000), market)
        assertEquals(listOf(galleryLess.id), market.read)
        // Neither gallery's photos can be fetched, as when a seller replaced them: both pages are
        // forgotten, so the next search reads them again.
        assertEquals(null, DetailCache.get(galleryLess.id)); assertEquals(null, DetailCache.get(withGallery.id))
    }

    @Test
    fun `a page read before is laid over the card for free`() {
        val listing = van("cached-${System.nanoTime()}")
        assertEquals(null, DetailEnricher.withCachedDetail(listing).vehicle?.mileageKm)
        DetailCache.put(listing.id, ListingDetail(description = "Erstzulassung: 11/2015\nKilometerstand: 375.572 km\nGetriebe: Schaltgetriebe"))
        val read = DetailEnricher.withCachedDetail(listing)
        assertEquals(375_572, read.vehicle?.mileageKm)
        assertEquals(2015, read.vehicle?.firstRegYear)
    }

    @Test
    fun `a page cached under an older reading has its wheelbase read again from its text`() {
        val listing = van("wheelbase-${System.nanoTime()}").copy(title = "Volkswagen Crafter Kasten mittlerer Radstand 2.0 TDI 35 HD")
        DetailCache.put(listing.id, ListingDetail(
            vehicle = io.github.tieo.arbay.model.VehicleInfo(mileageKm = 134_106, wheelbaseMm = 2000),
            description = "Räder/Reifen:\nRadstand mittel\n\nKilometerstand: 134.106 km",
        ))
        assertEquals(null, DetailEnricher.withCachedDetail(listing).vehicle?.wheelbaseMm)
        DetailCache.put(listing.id, ListingDetail(
            vehicle = io.github.tieo.arbay.model.VehicleInfo(wheelbaseMm = 2000),
            description = "Motor 2,0 Ltr. - 103 kW TDI, Radstand 3640 mm",
        ))
        assertEquals(3640, DetailEnricher.withCachedDetail(listing).vehicle?.wheelbaseMm)
    }

    private class Pages : Crawler {
        override val platformId = PlatformId.KLEINANZEIGEN
        val read = mutableListOf<String>()
        override suspend fun search(query: SearchQuery) = emptyList<Listing>()
        override suspend fun fetchDetail(listing: Listing): ListingDetail { read += listing.id; return ListingDetail(description = "Getriebe: Schaltgetriebe") }
    }

    @Test
    fun `a watch run reads every unread page and each one restarts the crawl clock, a person's search reads the cheapest 25`() = runBlocking {
        val stamp = System.nanoTime()
        val gearbox = CarFilters(transmission = io.github.tieo.arbay.model.Transmission.AUTOMATIC)
        val attended = Pages()
        DetailEnricher.enrich((1..40).map { van("a$it-$stamp") }, gearbox, attended)
        assertEquals(25, attended.read.size)

        val watch = Pages()
        val vans = (1..40).map { van("w$it-$stamp") }
        // A limit no run of forty pages fits in unless each page read starts it over.
        val enriched = withCrawlLimitOrNull(10_000) {
            DetailEnricher.enrich(vans, gearbox, object : Crawler by watch {
                override suspend fun fetchDetail(listing: Listing): ListingDetail? { kotlinx.coroutines.delay(400); return watch.fetchDetail(listing) }
            }, unattended = true)
        }
        assertEquals(40, watch.read.size)
        assertEquals(List(40) { io.github.tieo.arbay.model.Transmission.MANUAL }, enriched?.map { it.vehicle?.gearbox })
    }
}
