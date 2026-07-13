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
}
