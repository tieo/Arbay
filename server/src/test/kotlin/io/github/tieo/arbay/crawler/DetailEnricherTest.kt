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
}
