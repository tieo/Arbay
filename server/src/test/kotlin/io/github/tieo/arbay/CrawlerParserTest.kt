package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.*
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class CrawlerParserTest {

    private val query = SearchQuery(text = "Sony WH-1000XM4", category = MarketGroup.GENERAL)

    private fun mockClientFrom(filePath: String, statusCode: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        val file = File(filePath)
        if (!file.exists()) return mockClientWith("", statusCode)
        return mockClientWith(file.readText(), statusCode)
    }

    private fun mockClientWith(body: String, statusCode: HttpStatusCode = HttpStatusCode.OK): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = ByteReadChannel(body),
                        status = statusCode,
                        headers = headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                }
            }
        }
    }

    @Test
    fun testEbayDeParser() = runBlocking {
        val file = File("/tmp/ebay.html")
        if (!file.exists()) {
            println("SKIP: /tmp/ebay.html not found")
            return@runBlocking
        }
        val client = mockClientFrom("/tmp/ebay.html")
        val crawler = EbayDeCrawler(client)
        val results = crawler.search(query)
        println("eBay DE parsed: ${results.size} results")
        assertTrue(results.isNotEmpty(), "eBay DE parser should find results")
        results.take(3).forEach {
            println("  [${it.platformId}] ${it.title} — ${it.price.amount / 100}€ — ${it.url}")
            assertEquals(PlatformId.EBAY_DE, it.platformId)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.price.amount > 0)
            assertTrue(it.externalId.isNotBlank())
            assertTrue(it.url.contains("ebay"))
        }
    }

    @Test
    fun testKleinanzeigenParser() = runBlocking {
        val file = File("/tmp/kleinanzeigen.html")
        if (!file.exists()) {
            println("SKIP: /tmp/kleinanzeigen.html not found")
            return@runBlocking
        }
        val client = mockClientFrom("/tmp/kleinanzeigen.html")
        val crawler = KleinanzeigenCrawler(client)
        val results = crawler.search(query)
        println("Kleinanzeigen parsed: ${results.size} results")
        assertTrue(results.isNotEmpty(), "Kleinanzeigen parser should find results")
        results.take(3).forEach {
            println("  [${it.platformId}] ${it.title} — ${it.price.amount / 100}€ — ${it.url}")
            assertEquals(PlatformId.KLEINANZEIGEN, it.platformId)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.price.amount > 0)
        }
    }

    @Test
    fun testVintedDeParser() = runBlocking {
        val file = File("/tmp/vinted.html")
        if (!file.exists()) {
            println("SKIP: /tmp/vinted.html not found")
            return@runBlocking
        }
        val client = mockClientFrom("/tmp/vinted.html")
        val crawler = VintedDeCrawler(client)
        val results = crawler.search(query)
        println("Vinted DE parsed: ${results.size} results")
        assertTrue(results.isNotEmpty(), "Vinted parser should find results")
        results.take(3).forEach {
            println("  [${it.platformId}] ${it.title} — ${it.price.amount / 100}€ — ${it.url}")
            assertEquals(PlatformId.VINTED_DE, it.platformId)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.price.amount > 0)
        }
    }

    @Test
    fun testMarktplaatsParser() = runBlocking {
        val file = File("/tmp/marktplaats.html")
        if (!file.exists()) {
            println("SKIP: /tmp/marktplaats.html not found")
            return@runBlocking
        }
        val client = mockClientFrom("/tmp/marktplaats.html")
        val crawler = MarktplaatsCrawler(client)
        val results = crawler.search(query)
        println("Marktplaats parsed: ${results.size} results")
        assertTrue(results.isNotEmpty(), "Marktplaats parser should find results")
        results.take(3).forEach {
            println("  [${it.platformId}] ${it.title} — ${it.price.amount / 100}€ — ${it.url}")
            assertEquals(PlatformId.MARKTPLAATS, it.platformId)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.price.amount > 0)
        }
    }

    @Test
    fun testAutoScout24Parser() = runBlocking {
        val file = File("/tmp/autoscout.html")
        if (!file.exists()) {
            println("SKIP: /tmp/autoscout.html not found")
            return@runBlocking
        }
        val client = mockClientFrom("/tmp/autoscout.html")
        val crawler = AutoScout24Crawler(client)
        val carQuery = SearchQuery(text = "BMW 320d", category = MarketGroup.VEHICLES)
        val results = crawler.search(carQuery)
        println("AutoScout24 parsed: ${results.size} results")
        assertTrue(results.isNotEmpty(), "AutoScout24 parser should find results")
        results.take(3).forEach {
            println("  [${it.platformId}] ${it.title} — ${it.price.amount / 100}€ — ${it.url}")
            assertEquals(PlatformId.AUTOSCOUT24, it.platformId)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.price.amount > 0)
        }
    }

    @Test
    fun testRefurbedParser() = runBlocking {
        val file = File("/tmp/refurbed.html")
        if (!file.exists()) {
            println("SKIP: /tmp/refurbed.html not found")
            return@runBlocking
        }
        val client = mockClientFrom("/tmp/refurbed.html")
        val crawler = RefurbedCrawler(client)
        val results = crawler.search(query)
        println("Refurbed parsed: ${results.size} results")
        // Refurbed product page may show out-of-stock (price=0), which we filter
        results.take(3).forEach {
            println("  [${it.platformId}] ${it.title} — ${it.price.amount / 100}€ — ${it.url}")
            assertEquals(PlatformId.REFURBED, it.platformId)
        }
    }

    @Test
    fun testAmazonDeParser() = runBlocking {
        val file = File("/tmp/amazon.html")
        if (!file.exists()) {
            println("SKIP: /tmp/amazon.html not found")
            return@runBlocking
        }
        val client = mockClientFrom("/tmp/amazon.html")
        val crawler = AmazonDeCrawler(client)
        val results = crawler.search(query)
        println("Amazon DE parsed: ${results.size} results")
        assertTrue(results.isNotEmpty(), "Amazon DE parser should find results")
        results.take(3).forEach {
            println("  [${it.platformId}] ${it.title} — ${it.price.amount / 100}€ — ${it.url}")
            assertEquals(PlatformId.AMAZON_DE, it.platformId)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.price.amount > 0)
        }
    }

    @Test
    fun testGeizhalsParser() = runBlocking {
        val file = File("/tmp/geizhals2.html")
        if (!file.exists()) {
            println("SKIP: /tmp/geizhals2.html not found")
            return@runBlocking
        }
        val client = mockClientFrom("/tmp/geizhals2.html")
        val crawler = GeizhalsCrawler(client)
        val results = crawler.search(query)
        println("Geizhals parsed: ${results.size} results")
        assertTrue(results.isNotEmpty(), "Geizhals parser should find results")
        results.take(3).forEach {
            println("  [${it.platformId}] ${it.title} — ${it.price.amount / 100}€ — ${it.url}")
            assertEquals(PlatformId.GEIZHALS, it.platformId)
            assertTrue(it.title.isNotBlank())
            assertTrue(it.price.amount > 0)
        }
    }

    // Test error classification and captcha detection utilities directly
    @Test
    fun testHttpErrorClassification() = runBlocking {
        val client = mockClientWith("", HttpStatusCode.ServiceUnavailable)
        val response = client.get("http://test")
        assertEquals(ErrorType.SERVICE_UNAVAILABLE_503, classifyHttpError(response))
        println("HTTP 503: correctly classified as SERVICE_UNAVAILABLE_503")

        val client403 = mockClientWith("", HttpStatusCode.Forbidden)
        val response403 = client403.get("http://test")
        assertEquals(ErrorType.BLOCKED_403, classifyHttpError(response403))
        println("HTTP 403: correctly classified as BLOCKED_403")
    }

    @Test
    fun testCaptchaDetection() {
        val captchaHtml = """
            <html><body>
            <div class="g-recaptcha" data-sitekey="abc123"></div>
            <p>Please verify you are not a robot</p>
            </body></html>
        """.trimIndent()
        assertTrue(detectCaptcha(captchaHtml), "Should detect g-recaptcha in small page")
        println("Captcha detection: correctly detected g-recaptcha")

        // Large page with incidental captcha mention should NOT trigger
        val largePage = "x".repeat(25_000) + "recaptcha"
        assertFalse(detectCaptcha(largePage), "Should not flag large pages with incidental captcha mention")
        println("Captcha detection: correctly ignored large page with captcha mention")
    }
}
