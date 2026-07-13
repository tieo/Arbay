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
}
