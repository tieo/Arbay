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
