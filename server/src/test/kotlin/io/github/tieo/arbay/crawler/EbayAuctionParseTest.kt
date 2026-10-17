package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.testing.offlineClient
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
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

    private val italian by lazy {
        EbayDeCrawler(offlineClient(), PlatformId.EBAY_IT, "ebay.it")
            .parseSearchResults(javaClass.getResource("/fixtures/ebay_it_auctions_2026.html")!!.readText())
            .associateBy { it.externalId }
    }

    @Test
    fun `an Italian auction card is an auction with its bids and its end`() {
        // "EUR 44,60 · 14 offerte · 4g 1h rimasti": a Crucial P3 1 TB that was learned as a price.
        val p3 = italian.getValue("206625228353")
        assertEquals(SaleType.AUCTION, p3.saleType)
        assertEquals(14, p3.bidCount)
        // "16 offerte · 8g 20h rimasti": a WD SN850X 2 TB learned the same way.
        assertEquals(16, italian.getValue("940017781488").bidCount)
        assertEquals(listOf(0, 0, 1, 6, 14, 16, 19), italian.values.map { it.bidCount }.sortedBy { it })
        assertTrue(italian.values.all { it.saleType == SaleType.AUCTION && it.auctionEndsAt != null })
    }

    @Test
    fun `an Italian card's delivery charge is part of what it costs`() {
        assertEquals(Money(4460 + 619, Currency.EUR), italian.getValue("206625228353").effectivePrice)
        val free = italian.values.filter { it.shipping?.free == true }
        assertEquals(1, free.size)
        // "+EUR 17,62 di spese di spedizione stimate", from the United Kingdom.
        assertTrue(italian.values.count { (it.shipping?.cost?.amount ?: 0) > 1500 } >= 2)
    }

    @Test
    fun `days left on the Italian site are counted in g`() {
        val (_, endsAt, _) = crawler.parseAuction(
            card("<div class='s-card__attribute-row'><span>3 offerte</span><span class='s-card__time'><span class='s-card__time-left'>2g rimasti</span></span></div>"), now,
        )
        assertEquals(now.plus(kotlin.time.Duration.parse("48h")), endsAt)
    }

    @Test
    fun `an Italian card names the seller's country, which decides import VAT`() {
        // "da Regno Unito" and "da Germania", each on a line of its own.
        assertEquals("GB", italian.getValue("940017781488").location?.country)
        assertEquals("DE", italian.getValue("206625228353").location?.country)
    }
}
