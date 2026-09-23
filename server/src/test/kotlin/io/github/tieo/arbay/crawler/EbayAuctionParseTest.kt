package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.testing.offlineClient
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.Instant
import org.jsoup.Jsoup

/**
 * eBay says on the card whether a price is a bid. Nothing read it, so an auction's current bid was
 * stored as the asking price: one Crucial module was alerted on at €10.50 and was €21.50 the next
 * day, still rising, with the same seller and the same photo.
 */
class EbayAuctionParseTest {

    private val crawler = EbayDeCrawler(offlineClient())
    private val now = Instant.parse("2026-09-10T12:00:00Z")

    private fun card(inner: String) = Jsoup.parse("<li class='s-card'>$inner</li>").selectFirst("li")!!

    @Test
    fun `a German auction card gives its bids and its end`() {
        val (type, endsAt, bids) = crawler.parseAuction(
            card("<span>EUR 21,50</span><span>3 Gebote</span><span>Noch 1Std 12Min</span>"), now,
        )
        assertEquals(SaleType.AUCTION, type)
        assertEquals(3, bids)
        assertEquals(now.plus(kotlin.time.Duration.parse("72m")), endsAt)
    }

    @Test
    fun `an English auction card reads the same way`() {
        val (type, endsAt, bids) = crawler.parseAuction(
            card("<span>US \$36.45</span><span>5 bids</span><span>4h 12m left</span>"), now,
        )
        assertEquals(SaleType.AUCTION, type)
        assertEquals(5, bids)
        assertEquals(now.plus(kotlin.time.Duration.parse("252m")), endsAt)
    }

    @Test
    fun `days left are counted too`() {
        val (_, endsAt, _) = crawler.parseAuction(
            card("<span>1 Gebot</span><span>Noch 2T 3Std</span>"), now,
        )
        assertEquals(now.plus(kotlin.time.Duration.parse("3060m")), endsAt)
    }

    @Test
    fun `a card that says nothing about bidding is a price someone is asking`() {
        val (type, endsAt, bids) = crawler.parseAuction(
            card("<span>EUR 150,00</span><span>Sofort-Kaufen</span><span>Kostenloser Versand</span>"), now,
        )
        assertEquals(SaleType.FIXED_PRICE, type)
        assertNull(endsAt)
        assertNull(bids)
    }
}
