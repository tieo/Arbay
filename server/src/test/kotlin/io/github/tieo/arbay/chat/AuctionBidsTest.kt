package io.github.tieo.arbay.chat

import io.github.tieo.arbay.model.AuctionBidRequest
import io.github.tieo.arbay.model.AuctionBidState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking

class AuctionBidsTest {
    init {
        AuctionBids.mostBidEur = 160.0
    }

    @kotlin.test.BeforeTest
    fun none() = AuctionBids.forgetAll()
    private fun request(item: String, ends: kotlin.time.Instant, allIn: Double = 160.0, shipping: Double = 6.19) =
        AuctionBidRequest("EBAY_DE:$item", "https://www.ebay.de/itm/$item?bolp=1", "Lexar NQ790 2TB", ends, shipping, allIn)

    @Test
    fun `the most a bid goes to is the all-in limit less shipping, to the cent`() {
        assertEquals(15_381L, AuctionBids.maxCents(160.0, 6.19))
        assertEquals(16_000L, AuctionBids.maxCents(160.0, 0.0))
        assertNull(AuctionBids.maxCents(6.0, 6.19))
        assertNull(AuctionBids.maxCents(Double.NaN, 0.0))
        assertNull(AuctionBids.maxCents(160.0, -1.0))
        assertEquals("820299930359", AuctionBids.itemOf("https://www.ebay.de/itm/820299930359?bolp=1"))
        assertNull(AuctionBids.itemOf("https://www.ebay.com/itm/820299930359"))
    }

    @Test
    fun `a bid is set up only on an eBay de auction running long enough, once`() {
        val now = Clock.System.now()
        val item = (100_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        assertFailsWith<IllegalArgumentException> { AuctionBids.ask(request(item, now + 1.minutes), now) }
        assertFailsWith<IllegalArgumentException> { AuctionBids.ask(request(item, now + 1.minutes).copy(url = "https://www.ebay.com/itm/$item"), now) }
        assertFailsWith<IllegalArgumentException> { AuctionBids.ask(request(item, now + 10.minutes, allIn = 5.0), now) }
        // Cents taken for euros: past Arbay's own most a bid may cost.
        assertFailsWith<IllegalArgumentException> { AuctionBids.ask(request(item, now + 10.minutes, allIn = 16_000.0), now) }
        assertFailsWith<IllegalArgumentException> { AuctionBids.ask(request(item, now + 10.minutes, allIn = 160.01), now) }
        assertFailsWith<IllegalArgumentException> { AuctionBids.ask(request(item, now + 10.minutes, allIn = Double.POSITIVE_INFINITY), now) }
        val bid = AuctionBids.ask(request(item, now + 10.minutes), now)
        assertEquals(153.81, bid.maxEur)
        assertEquals("https://www.ebay.de/itm/$item", bid.url)
        assertFailsWith<IllegalArgumentException> { AuctionBids.ask(request(item, now + 10.minutes), now) }
        assert(AuctionBids.cancel("EBAY_DE:$item"))
    }

    @Test
    fun `a bid whose placing is not known stands, and the next one's turn is skipped while it does`() = runBlocking {
        // Neuland is not set up here, so placing ends unanswered: it may stand, and counts as placed.
        val now = Clock.System.now()
        val first = (200_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        val second = (300_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        AuctionBids.ask(request(first, now + 10.minutes), now)
        AuctionBids.ask(request(second, now + 10.minutes + 30.seconds), now)
        AuctionBids.tick(now + 8.minutes + 30.seconds)
        assertEquals(AuctionBidState.PLACED, AuctionBids.all().single { it.listingId == "EBAY_DE:$first" }.state)
        AuctionBids.tick(now + 8.minutes + 45.seconds)
        val skipped = AuctionBids.all().single { it.listingId == "EBAY_DE:$second" }
        assertEquals(AuctionBidState.SKIPPED, skipped.state)
        AuctionBids.tick(now + 12.minutes)
        assertEquals(AuctionBidState.ENDED, AuctionBids.all().single { it.listingId == "EBAY_DE:$first" }.state)
        // An ended auction may have been won: the next bid's turn is skipped while it is not settled.
        val third = (400_000_000_000L + (Math.random() * 1e9).toLong()).toString()
        AuctionBids.ask(request(third, now + 20.minutes), now + 12.minutes)
        AuctionBids.tick(now + 18.minutes + 30.seconds)
        assertEquals(AuctionBidState.SKIPPED, AuctionBids.all().single { it.listingId == "EBAY_DE:$third" }.state)
    }

    @Test
    fun `no bid is set up without Arbay's own most a bid may cost`() {
        val was = AuctionBids.mostBidEur
        AuctionBids.mostBidEur = null
        try {
            val now = Clock.System.now()
            assertFailsWith<IllegalArgumentException> { AuctionBids.ask(request("500000000001", now + 10.minutes), now) }
        } finally {
            AuctionBids.mostBidEur = was
        }
    }
}
