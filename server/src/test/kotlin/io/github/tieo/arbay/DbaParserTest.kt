package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.DbaCrawler
import io.github.tieo.arbay.model.Currency
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.*

class DbaParserTest {

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
        javaClass.getResource("/fixtures/dba_search.html")!!.readText()

    @Test
    fun `parses listings from seoStructuredData fixture`() {
        val crawler = DbaCrawler(dummyClient())
        val results = crawler.parse(fixture())

        assertTrue(results.size >= 3, "Expected at least 3 listings, got ${results.size}")
        results.forEach { listing ->
            assertTrue(listing.title.length > 5, "Implausible title: ${listing.title}")
            assertTrue(listing.price.amount > 0, "Implausible price: ${listing.price}")
            assertEquals(Currency.DKK, listing.price.currency)
            assertTrue(listing.url.startsWith("https://www.dba.dk"), "Unexpected url: ${listing.url}")
        }
    }
}
