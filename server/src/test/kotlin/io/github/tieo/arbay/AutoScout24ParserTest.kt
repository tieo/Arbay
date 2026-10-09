package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.AutoScout24Crawler
import io.github.tieo.arbay.model.PlatformId
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.*

class AutoScout24ParserTest {

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
        javaClass.getResource("/fixtures/autoscout24_path.html")!!.readText()

    @Test
    fun `parses listings from path URL NEXT_DATA fixture`() {
        val crawler = AutoScout24Crawler(dummyClient())
        val results = crawler.parseFromNextData(fixture())

        assertNotNull(results, "__NEXT_DATA__ parsing should succeed on the fixture")
        assertTrue(results.size >= 10, "Expected at least 10 listings, got ${results.size}")
        results.forEach { listing ->
            assertTrue(
                listing.title.contains("Crafter", ignoreCase = true),
                "Every title should contain 'Crafter', got: ${listing.title}",
            )
            assertEquals(PlatformId.AUTOSCOUT24, listing.platformId)
            assertTrue(listing.externalId.isNotBlank())
            assertTrue(listing.price.amount > 0)
            assertTrue(listing.url.contains("autoscout24"))
        }
    }

    @Test
    fun `a dealer card carries the dealer's stars and review count`() {
        val results = assertNotNull(AutoScout24Crawler(dummyClient()).parseFromNextData(fixture()))
        val first = results.first()
        assertEquals(5.0, first.seller?.rating)
        assertEquals(52, first.seller?.reviewCount)
    }

    @Test
    fun `an ad page says when the ad went up`() {
        val page = """<html><body><script id="__NEXT_DATA__" type="application/json">""" +
            """{"props":{"pageProps":{"listingDetails":{"createdTimestampWithOffset":"2026-10-08T16:54:26.319Z",""" +
            """"vehicle":{"equipment":{"comfortAndConvenience":[{"id":"Klimaanlage"},{"id":"Tempomat"}]}}}}}}""" +
            """</script></body></html>"""
        assertEquals(
            kotlin.time.Instant.parse("2026-10-08T16:54:26.319Z"),
            AutoScout24Crawler(dummyClient()).listedAt(org.jsoup.Jsoup.parse(page)),
        )
        assertEquals("Klimaanlage, Tempomat", AutoScout24Crawler(dummyClient()).equipment(org.jsoup.Jsoup.parse(page)))
    }
}
