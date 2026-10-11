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
}
