package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.SellsByAuction
import io.github.tieo.arbay.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Only the markets that hold auctions may leave a listing's sale type open.
 *
 * Every other market sells at the stated price, and leaving that unsaid meant a screen of 131
 * listings offered "not stated" for 105 of them, so taking auctions out took those with them.
 */
class SaleTypeStatedTest {

    @Test
    fun `the auction markets are the ones that say so`() {
        val auctionHouses = PlatformId.entries
            .filter { CrawlerRegistry.crawlerFor(it) is SellsByAuction }
            .toSet()
        assertTrue(PlatformId.EBAY_DE in auctionHouses, "eBay sells by auction")
        assertTrue(PlatformId.RICARDO in auctionHouses, "ricardo sells by auction")
        assertFalse(PlatformId.KLEINANZEIGEN in auctionHouses, "Kleinanzeigen holds no auctions")
        assertFalse(PlatformId.MOBILE_DE in auctionHouses, "mobile.de holds no auctions")
    }
}
