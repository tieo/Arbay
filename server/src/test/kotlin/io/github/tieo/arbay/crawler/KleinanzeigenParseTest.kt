package io.github.tieo.arbay.crawler

import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kleinanzeigen's result cards, as the site actually serves them.
 *
 * The fixture is a real "grigri" search page captured on 2026-09-07, after Kleinanzeigen replaced
 * the class names every selector here used to name. Nothing threw when that happened: the search
 * simply returned nothing, on the market this app depends on most, for as long as nobody looked.
 * A page in the repo is what turns that back into a test failure.
 */
class KleinanzeigenParseTest {

    private val crawler = KleinanzeigenCrawler(HttpClient())
    private val html by lazy {
        requireNotNull(javaClass.getResourceAsStream("/fixtures/kleinanzeigen_grigri_2026.html"))
            .bufferedReader().readText()
    }

    @Test
    fun `the cards on a real search page are found`() {
        val listings = crawler.parseSearchResults(html)
        assertTrue(listings.size >= 20, "expected a page of ads, got ${listings.size}")
    }

    @Test
    fun `each card carries the fields a result needs`() {
        val listings = crawler.parseSearchResults(html)
        assertTrue(listings.all { it.title.isNotBlank() }, "a listing came back without a title")
        assertTrue(listings.all { it.url.startsWith("https://www.kleinanzeigen.de/s-anzeige/") })
        assertTrue(listings.all { it.externalId.isNotBlank() })
        assertTrue(listings.count { it.imageUrls.isNotEmpty() } >= listings.size / 2)
        assertTrue(listings.count { it.location != null } >= listings.size / 2, "locations missing")
        assertTrue(listings.count { it.listingDate != null } >= listings.size / 2, "dates missing")
    }

    @Test
    fun `the prices are the ones on the page`() {
        val listings = crawler.parseSearchResults(html)
        val silver = listings.first { it.title.contains("Silber mit Karabiner") }
        assertEquals(46_00, silver.price.amount)
        assertEquals("24887", silver.location?.zip)
        // Every price is a plausible amount rather than a stray number picked off the card.
        assertTrue(listings.all { it.price.amount in 0..500_000 }, "a price came out implausible")
    }

    @Test
    fun `a search for a belay device finds belay devices`() {
        val listings = crawler.parseSearchResults(html)
        val petzl = listings.count { it.title.contains("Petzl", ignoreCase = true) }
        assertTrue(petzl >= 5, "expected Petzl devices on a grigri page, found $petzl")
    }
}

