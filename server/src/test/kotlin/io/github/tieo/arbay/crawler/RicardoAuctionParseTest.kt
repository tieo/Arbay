package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.SaleType
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ricardo is an auction house that also sells at fixed prices, and it says which each listing is.
 * The crawler used to take the bid as the price where there was no buy-now, with a comment calling
 * it "what a buyer would pay right now" — which is true only until somebody bids again.
 */
class RicardoAuctionParseTest {

    private val crawler = RicardoCrawler(HttpClient())
    private val html by lazy {
        requireNotNull(javaClass.getResourceAsStream("/fixtures/ricardo_search_2026.html"))
            .bufferedReader().readText()
    }

    @Test
    fun `a real search page parses into listings`() {
        assertTrue(crawler.parse(html).size >= 5, "expected the page's articles")
    }

    @Test
    fun `every listing says how it is sold`() {
        val listings = crawler.parse(html)
        assertTrue(listings.all { it.saleType != null }, "ricardo states this on every article")
    }

    @Test
    fun `an auction carries the moment it ends and its bids, a fixed price does not`() {
        val listings = crawler.parse(html)
        listings.filter { it.saleType == SaleType.AUCTION }.forEach {
            assertNotNull(it.auctionEndsAt, "an auction without an end cannot be timed: ${it.title}")
        }
        listings.filter { it.saleType == SaleType.FIXED_PRICE }.forEach {
            assertNull(it.auctionEndsAt, "a price someone is asking does not run out: ${it.title}")
        }
    }
}
