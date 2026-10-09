package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.SellerType
import io.github.tieo.arbay.testing.offlineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who sells each listing, as each market's result page states it, which is what a blocked dealer is
 * matched by.
 *
 * The `*_sellers.html` fixtures are made up in the shape of the real pages, with invented names and
 * numbers. The real pages already in the fixtures check that every card on them finds its seller.
 */
class SellerParsingTest {

    private fun fixture(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")).bufferedReader().readText()

    @Test
    fun `AutoScout24 names a dealer by its account number, company name and page`() {
        val listings = AutoScout24Crawler(offlineClient()).parseFromNextData(fixture("autoscout24_sellers.html"))!!
        val dealer = listings.first { it.externalId.startsWith("aaaaaaaa") }.seller!!
        assertEquals("900001", dealer.id)
        assertEquals("Beispiel Nutzfahrzeuge GmbH", dealer.name)
        assertEquals(SellerType.BUSINESS, dealer.type)
        assertEquals("https://www.autoscout24.de/haendler/beispiel-nutzfahrzeuge", dealer.url)
    }

    @Test
    fun `AutoScout24 keeps a private seller's number and not their name`() {
        val listings = AutoScout24Crawler(offlineClient()).parseFromNextData(fixture("autoscout24_sellers.html"))!!
        val private = listings.first { it.externalId.startsWith("bbbbbbbb") }.seller!!
        assertEquals("900002", private.id)
        assertEquals(SellerType.PRIVATE, private.type)
        assertNull(private.name)
        assertNull(private.url)
    }

    @Test
    fun `mobile_de names the seller of each card from the page's streamed data`() {
        val listings = MobileDeCrawler(offlineClient()).parseSearchResults(fixture("mobilede_sellers.html"))
        assertEquals(2, listings.size)
        val dealer = listings.first { it.externalId == "100000001" }.seller!!
        assertEquals("700001", dealer.id)
        assertEquals("Beispiel Nutzfahrzeuge GmbH", dealer.name)
        assertEquals(SellerType.BUSINESS, dealer.type)
        assertEquals("https://www.mobile.de/bewertungen/BEISPIELNFZ", dealer.url)
        val private = listings.first { it.externalId == "100000002" }.seller!!
        assertEquals("700002", private.id)
        assertEquals(SellerType.PRIVATE, private.type)
        assertNull(private.name)
    }

    @Test
    fun `every card on a real mobile_de page finds its seller`() {
        val listings = MobileDeCrawler(offlineClient()).parseSearchResults(fixture("mobilede_search.html"))
        assertTrue(listings.size >= 20)
        assertTrue(listings.all { it.seller?.id != null }, "without a seller: " + listings.filter { it.seller?.id == null }.map { it.externalId })
        assertTrue(listings.all { !it.seller?.name.isNullOrBlank() }, "every card on that page is a dealer's")
    }

    @Test
    fun `Kleinanzeigen knows a seller by account number and kind alone`() {
        val listings = KleinanzeigenCrawler(offlineClient()).parseSearchResults(fixture("kleinanzeigen_sellers.html"))
        assertEquals(2, listings.size)
        val commercial = listings.first { it.externalId == "3000000001" }.seller!!
        assertEquals("50000001", commercial.id)
        assertEquals(SellerType.BUSINESS, commercial.type)
        assertNull(commercial.name)
        assertEquals(SellerType.PRIVATE, listings.first { it.externalId == "3000000002" }.seller!!.type)
    }

    @Test
    fun `every card on a real Kleinanzeigen page finds its seller`() {
        val listings = KleinanzeigenCrawler(offlineClient()).parseSearchResults(fixture("kleinanzeigen_grigri_2026.html"))
        assertTrue(listings.size >= 20)
        assertTrue(listings.all { it.seller?.id != null }, "without a seller: " + listings.filter { it.seller?.id == null }.map { it.externalId })
    }
}
