package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.TruckScout24Crawler
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.*

class TruckScout24ParserTest {

    private fun dummyClient() = HttpClient(MockEngine) {
        engine {
            addHandler {
                respond(
                    content = ByteReadChannel(""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/html"),
                )
            }
        }
    }

    private fun fixture(): String =
        javaClass.getResource("/fixtures/truckscout24_search.html")!!.readText()

    @Test
    fun `parses listing cards from search fixture`() {
        val crawler = TruckScout24Crawler(dummyClient())
        val results = crawler.parse(fixture())

        assertTrue(results.size >= 5, "Expected at least 5 listings, got ${results.size}")
        results.forEach { listing ->
            assertTrue(listing.title.length > 5, "Implausible title: ${listing.title}")
            assertTrue(listing.price.amount > 100_00, "Implausible price: ${listing.price}")
            assertTrue(listing.url.startsWith("https://www.truckscout24.de/tsp/"), "Unexpected url: ${listing.url}")
        }
    }

    /** Dealers headline the net price ("14.900 € Festpreis zzgl. MwSt.") and add the gross in
     *  brackets; a private buyer pays the gross, so that is the listing's price. */
    @Test
    fun `a card's place is its town and country, not the site's distance`() {
        val results = TruckScout24Crawler(dummyClient()).parse(fixture())
        val places = results.mapNotNull { it.location }
        assertTrue(places.isNotEmpty())
        places.forEach { place ->
            assertFalse(place.city.orEmpty().contains(Regex("""\d+\s*km""")), "distance read as the town: ${place.city}")
            assertTrue(place.country?.length == 2, "no country: $place")
        }
    }

    @Test
    fun `a dealer's net price is read as the gross a buyer pays`() {
        val html = fixture()
        val stated = org.jsoup.Jsoup.parse(html).select("section[data-listing-id]").mapNotNull { card ->
            val text = card.selectFirst("[data-grid=price]")?.text() ?: return@mapNotNull null
            val gross = Regex("""(\d[\d.]*)\s*€\s*brutto""").find(text)?.groupValues?.get(1) ?: return@mapNotNull null
            card.attr("data-listing-id") to gross.replace(".", "").toLong() * 100
        }.toMap()
        assertTrue(stated.isNotEmpty(), "the fixture holds dealer cards with a gross figure")
        val listings = TruckScout24Crawler(dummyClient()).parse(html).associateBy { it.externalId }
        stated.forEach { (id, cents) -> assertEquals(cents, listings.getValue(id).price.amount, "listing $id") }
    }

    @Test
    fun `reads an ad page's description and technical rows`() {
        val html = javaClass.getResource("/fixtures/truckscout24_detail.html")!!.readText()
        val detail = assertNotNull(TruckScout24Crawler(dummyClient()).parseDetail(html))
        val vehicle = assertNotNull(detail.vehicle)
        assertEquals(150_000, vehicle.mileageKm)
        assertEquals(130, vehicle.powerKw)
        assertEquals(2021, vehicle.firstRegYear)
        assertEquals(4, vehicle.firstRegMonth)
        assertEquals(io.github.tieo.arbay.model.Transmission.AUTOMATIC, vehicle.gearbox)
        assertEquals(3, vehicle.seats)
        assertEquals(6, vehicle.emissionClassEuro)
        val description = assertNotNull(detail.description)
        assertTrue("VW Crafter Kasten L3H3\nTempomat" in description.replace("1.Hand\n", ""), description)
        assertTrue("Laderaumlänge: 3.300 mm" in description, description)
    }
}
