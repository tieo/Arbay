package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.MobileDeCrawler
import io.github.tieo.arbay.model.Currency
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.*

class MobileDeParserTest {

    private fun dummyClient() = HttpClient(MockEngine) {
        engine {
            addHandler {
                respond(ByteReadChannel(""), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            }
        }
    }

    private fun fixture(): String =
        javaClass.getResource("/fixtures/mobilede_search.html")!!.readText()

    @Test
    fun `parses current mobile_de SRP data-testid structure`() {
        val results = MobileDeCrawler(dummyClient()).parseSearchResults(fixture())

        assertTrue(results.size >= 10, "Expected at least 10 listings, got ${results.size}")
        results.forEach { listing ->
            assertTrue(listing.title.length > 5, "Implausible title: ${listing.title}")
            assertTrue(listing.price.amount > 0, "Missing price: ${listing.title}")
            assertEquals(Currency.EUR, listing.price.currency)
            assertTrue(listing.url.contains("/fahrzeuge/details"), "Unexpected url: ${listing.url}")
        }
        assertTrue(results.any { it.title.contains("Crafter", ignoreCase = true) }, "No Crafter titles parsed")
    }

    @Test
    fun `reads what an ad page adds to the card`() {
        val html = javaClass.getResource("/fixtures/mobilede_ad.html")!!.readText()
        val detail = assertNotNull(MobileDeCrawler(dummyClient()).parseAd(html))
        val vehicle = assertNotNull(detail.vehicle)
        assertEquals(io.github.tieo.arbay.model.Drivetrain.FWD, vehicle.drivetrain)
        assertEquals(4490, vehicle.wheelbaseMm)
        assertEquals(3, vehicle.seats)
        assertEquals(6, vehicle.emissionClassEuro)
        val description = assertNotNull(detail.description)
        assertTrue(description.startsWith("Volkswagen Crafter 2.0TDI*DSG"), description.take(80))
        assertTrue("Ausstattungslinie: 35 lang Hochdach FWD Trendline" in description)
        assertTrue("Radstand 4490 mm" in description)
    }

    @Test
    fun `a long Hochdach ad reads as VW's long high roof van`() {
        val html = javaClass.getResource("/fixtures/mobilede_ad.html")!!.readText()
        val detail = assertNotNull(MobileDeCrawler(dummyClient()).parseAd(html))
        val reading = io.github.tieo.arbay.crawler.VanDimensions.read(
            "Volkswagen Crafter ${detail.description}", wheelbaseMm = detail.vehicle?.wheelbaseMm,
        )
        assertEquals(io.github.tieo.arbay.model.VanSize.HIGH_ROOF, reading.height)
        assertEquals(io.github.tieo.arbay.model.VanSize.LONG, reading.length)
    }

    @Test
    fun `a page without technical data is no ad`() {
        assertNull(MobileDeCrawler(dummyClient()).parseAd("<html><body>Access denied</body></html>"))
    }
}
