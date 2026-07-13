package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.OtomotoCrawler
import io.github.tieo.arbay.model.Currency
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.*

class OtomotoParserTest {

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
        javaClass.getResource("/fixtures/otomoto_search.html")!!.readText()

    @Test
    fun `parses listings from search fixture`() {
        val crawler = OtomotoCrawler(dummyClient())
        val results = crawler.parse(fixture())

        assertTrue(results.size >= 5, "Expected at least 5 listings, got ${results.size}")
        results.forEach { listing ->
            assertTrue(listing.title.length > 5, "Implausible title: ${listing.title}")
            assertTrue(listing.price.amount > 0, "Implausible price: ${listing.price}")
            assertEquals(Currency.PLN, listing.price.currency, "Expected PLN price: ${listing.price}")
            assertTrue(listing.url.startsWith("https://www.otomoto.pl"), "Unexpected url: ${listing.url}")
        }
    }
}
