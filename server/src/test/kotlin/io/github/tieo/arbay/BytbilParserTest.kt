package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.BytbilCrawler
import io.github.tieo.arbay.model.Currency
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.*

class BytbilParserTest {

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
        javaClass.getResource("/fixtures/bytbil_search.html")!!.readText()

    @Test
    fun `parses listing cards from search fixture`() {
        val crawler = BytbilCrawler(dummyClient())
        val results = crawler.parse(fixture())

        assertTrue(results.size >= 3, "Expected at least 3 listings, got ${results.size}")
        results.forEach { listing ->
            assertTrue(listing.title.length > 5, "Implausible title: ${listing.title}")
            assertTrue(listing.price.amount > 0, "Implausible price: ${listing.price}")
            assertEquals(Currency.SEK, listing.price.currency, "Expected SEK currency: ${listing.price}")
            assertTrue(listing.url.startsWith("https://www.bytbil.com"), "Unexpected url: ${listing.url}")
        }
    }
}
