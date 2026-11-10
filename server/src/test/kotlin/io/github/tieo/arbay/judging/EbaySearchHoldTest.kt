package io.github.tieo.arbay.judging

import io.github.tieo.arbay.neuland.NeulandOffers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EbaySearchHoldTest {
    @Test
    fun `eBay's error page holds every eBay search for a while and nothing else`() {
        val t = 1_000_000_000_000L
        NeulandOffers.noteRefusal("ebay.de", "https://www.ebay.de/sch read as errorpage", t)
        assertTrue(NeulandOffers.searchHeld("ebay.fr", t + 60_000))
        assertFalse(NeulandOffers.searchHeld("kleinanzeigen.de", t + 60_000))
        assertFalse(NeulandOffers.searchHeld("ebay.de", t + 31 * 60_000))
        NeulandOffers.noteRefusal("idealo.de", "read as errorpage", t + 32 * 60_000)
        assertFalse(NeulandOffers.searchHeld("ebay.de", t + 33 * 60_000))
    }
}
