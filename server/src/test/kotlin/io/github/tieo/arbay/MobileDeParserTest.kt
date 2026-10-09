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

    private fun card(n: Int, id: Long, box: String) = """
        <div data-testid="base-result-listing-$n"><a href="/fahrzeuge/details.html?id=$id">
        <h2 data-testid="listing-title-card-view">Volkswagen Crafter</h2>
        <span data-testid="main-price-label">23.950 €</span></a>
        <div data-testid="seller-info"><div>$box</div></div></div>"""

    @Test
    fun `reads the place and the dealer from the seller box`() {
        val flight = "{\"id\":2,\"sellerId\":77,\"contact\":{\"enumType\":\"DEALER\"}}"
        val payload = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(),
            kotlinx.serialization.json.JsonPrimitive("1:$flight\n"))
        val html = "<html><body>" +
            card(1, 1, "<div><span>Privatanbieter</span><span>91056 Erlangen</span></div>") +
            card(2, 2, "<span>Autohaus Muster GmbH</span><span>21244 Buchholz in der Nordheide</span><span><span>4.9 Sterne</span></span>") +
            "<script>self.__next_f.push([1,$payload])</script></body></html>"
        val (privateOne, dealerOne) = MobileDeCrawler(dummyClient()).parseSearchResults(html)
        assertEquals("91056", privateOne.location?.zip)
        assertEquals("Erlangen", privateOne.location?.city)
        assertEquals("Buchholz in der Nordheide", dealerOne.location?.city)
        assertEquals("Autohaus Muster GmbH", dealerOne.seller?.name)
        assertEquals("77", dealerOne.seller?.id)
    }
}
