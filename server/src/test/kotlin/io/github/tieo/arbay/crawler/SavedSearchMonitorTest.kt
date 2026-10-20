package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.NotificationSubfilter
import io.github.tieo.arbay.model.learning
import io.github.tieo.arbay.model.SaleType
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import io.github.tieo.arbay.model.displayName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Whether a fresh listing is worth a push notification under a search's own subfilter, not just
 *  a count on the bookmark. Every case here is a subfilter someone could plausibly set. */
class SavedSearchMonitorTest {

    private fun listing(
        title: String,
        priceEur: Int,
        condition: Condition? = null,
        platform: io.github.tieo.arbay.model.PlatformId = io.github.tieo.arbay.model.PlatformId.KLEINANZEIGEN,
    ) = Listing(
        id = title.hashCode().toString(),
        platformId = platform,
        externalId = title.hashCode().toString(),
        url = "https://example.com/$title",
        title = title,
        price = Money(priceEur * 100L, Currency.EUR),
        condition = condition,
        scrapedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun sf(
        minPriceEur: Int? = null,
        maxPriceEur: Int? = null,
        condition: String? = null,
        mustContainAnyOf: List<String> = emptyList(),
        excludeKeywords: List<String> = emptyList(),
    ) = NotificationSubfilter(
        id = "sf1", name = "test",
        minPriceEur = minPriceEur, maxPriceEur = maxPriceEur, condition = condition,
        mustContainAnyOf = mustContainAnyOf, excludeKeywords = excludeKeywords,
    )

    @Test
    fun `a bare subfilter matches anything the search already found`() {
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 8000), sf()))
    }

    @Test
    fun `max price excludes anything over it, keeps anything at or under`() {
        val filter = sf(maxPriceEur = 8000)
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 8000), filter))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 7999), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 8001), filter))
    }

    @Test
    fun `min price excludes anything under it`() {
        val filter = sf(minPriceEur = 5000)
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 4999), filter))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 5000), filter))
    }

    @Test
    fun `a price band combines both bounds`() {
        val filter = sf(minPriceEur = 5000, maxPriceEur = 8000)
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 4000), filter))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 6000), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314", 9000), filter))
    }

    @Test
    fun `condition NEW keeps only NEW, USED keeps everything else, null is Any`() {
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("x", 100, Condition.NEW), sf(condition = "NEW")))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("x", 100, Condition.USED), sf(condition = "NEW")))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("x", 100, null), sf(condition = "NEW")))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("x", 100, Condition.USED), sf(condition = "USED")))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("x", 100, Condition.NEW), sf(condition = "USED")))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("x", 100, Condition.NEW), sf(condition = null)))
    }

    @Test
    fun `mustContainAnyOf requires at least one of the terms in the title, case-insensitively`() {
        val filter = sf(mustContainAnyOf = listOf("Automatik", "automatic"))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 AUTOMATIK", 100), filter))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 automatic", 100), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Schaltgetriebe", 100), filter))
    }

    @Test
    fun `excludeKeywords rejects a title containing any of them, case-insensitively`() {
        val filter = sf(excludeKeywords = listOf("Unfall", "defekt"))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 UNFALLWAGEN", 100), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Motor defekt", 100), filter))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 top Zustand", 100), filter))
    }

    @Test
    fun `every criterion must hold at once`() {
        val filter = sf(maxPriceEur = 9000, condition = "USED", mustContainAnyOf = listOf("automatik"))
        assertTrue(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Automatik", 8000, Condition.USED), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Automatik", 9500, Condition.USED), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Automatik", 8000, Condition.NEW), filter))
        assertFalse(SavedSearchMonitor.matchesSubfilter(listing("Sprinter 314 Schaltung", 8000, Condition.USED), filter))
    }

    @Test
    fun `a subfilter named with a placeholder is described by its criteria`() {
        // Both saved searches on the phone were named ".", so every notification they raised read
        // ". · parkettschleifmaschine". A name has to contain something to be a name.
        assertEquals("up to €550", NotificationSubfilter(id = "a", name = ".", maxPriceEur = 550).displayName)
        assertEquals("up to €550", NotificationSubfilter(id = "a", name = "  ", maxPriceEur = 550).displayName)
        assertEquals("under 600", NotificationSubfilter(id = "a", name = "under 600", maxPriceEur = 550).displayName)
    }

    // === auctions interrupt only when they are nearly over ===

    private val now = Instant.parse("2026-09-10T12:00:00Z")

    private fun auction(endsInMinutes: Long?) = listing("Crucial 32GB CT32G4SFD832A", 1050).copy(
        saleType = SaleType.AUCTION,
        auctionEndsAt = endsInMinutes?.let { now.plus(kotlin.time.Duration.parse("${it}m")) },
    )

    @Test
    fun `an auction ending before the next look is worth interrupting for`() {
        // A search that looks every three hours: an auction ending in two is the last chance to see
        // it before the bidding is over.
        assertTrue(SavedSearchMonitor.worthInterrupting(auction(120), intervalMinutes = 180, now = now))
    }

    @Test
    fun `an auction with hours to run waits on the bookmark`() {
        // The price is a bid that has not finished rising, so there is nothing to say yet.
        assertFalse(SavedSearchMonitor.worthInterrupting(auction(600), intervalMinutes = 180, now = now))
    }

    @Test
    fun `an auction that never says when it ends cannot be timed`() {
        assertFalse(SavedSearchMonitor.worthInterrupting(auction(null), intervalMinutes = 180, now = now))
    }

    @Test
    fun `a fixed price listing is unaffected`() {
        val fixed = listing("Crucial 32GB CT32G4SFD832A", 14000).copy(saleType = SaleType.FIXED_PRICE)
        assertTrue(SavedSearchMonitor.worthInterrupting(fixed, intervalMinutes = 180, now = now))
        val unknown = listing("Crucial 32GB CT32G4SFD832A", 14000)
        assertTrue(SavedSearchMonitor.worthInterrupting(unknown, intervalMinutes = 180, now = now))
    }

    @Test
    fun `a search that looks more often is told later`() {
        // Same auction, two searches: the half-hourly one has another look before it ends, the
        // six-hourly one does not.
        assertFalse(SavedSearchMonitor.worthInterrupting(auction(120), intervalMinutes = 30, now = now))
        assertTrue(SavedSearchMonitor.worthInterrupting(auction(120), intervalMinutes = 360, now = now))
    }

    @Test
    fun `the same van on two markets counts once, as its best scored copy`() {
        fun van(id: String) = Listing(
            id = id, platformId = io.github.tieo.arbay.model.PlatformId.valueOf(id.substringBefore(':')), externalId = id, url = "https://example.invalid/$id",
            title = "Volkswagen Crafter", price = Money(18_950_00, Currency.EUR), scrapedAt = Instant.fromEpochSeconds(0),
            vehicle = io.github.tieo.arbay.model.VehicleInfo(firstRegYear = 2023, mileageKm = 88_999),
        )
        val ranked = bestCopyEach(listOf(van("KLEINANZEIGEN:1") to 0.79, van("MOBILE_DE:2") to 0.88)) { it }
        assertEquals(listOf("MOBILE_DE:2" to 0.88), ranked.map { it.first.id to it.second })
    }

    @Test
    fun `a market over its request window is waited for only while that ends before the deadline`() = kotlinx.coroutines.runBlocking {
        val market = "budget-test-${System.nanoTime()}"
        assertTrue(RequestMonitor.awaitBudget(market, 0))
        repeat(45) { RequestMonitor.recordRequest(market) }
        assertFalse(RequestMonitor.awaitBudget(market, kotlin.time.Clock.System.now().toEpochMilliseconds() + 1_000))
    }

    @Test
    fun `a fetch takes a request from its market's window and is refused only while the window stays spent`() = kotlinx.coroutines.runBlocking {
        val market = "acquire-test-${System.nanoTime()}"
        val later = kotlin.time.Clock.System.now().toEpochMilliseconds() + 1_000
        repeat(45) { assertTrue(RequestMonitor.acquire(market, later)) }
        assertTrue(RequestMonitor.overBudget(market))
        assertFalse(RequestMonitor.acquire(market, later))
    }

    @Test
    fun `a learning rule tells only about finds below the best it saw`() {
        val learned = sf().copy(learnUntil = Instant.fromEpochMilliseconds(0), bestSeenEur = 130)
        assertTrue(SavedSearchMonitor.beatsTheBestSeen(listing("2TB NVMe", 129), learned))
        assertFalse(SavedSearchMonitor.beatsTheBestSeen(listing("2TB NVMe", 130), learned))
        assertTrue(SavedSearchMonitor.beatsTheBestSeen(listing("2TB NVMe", 500), sf()))
        assertTrue(SavedSearchMonitor.beatsTheBestSeen(listing("2TB NVMe", 500), sf().copy(learnUntil = Instant.fromEpochMilliseconds(0))))
        assertFalse(SavedSearchMonitor.beatsTheBestSeen(listing("2TB NVMe for parts", 20, Condition.PARTS_ONLY), learned))
        assertFalse(SavedSearchMonitor.beatsTheBestSeen(listing("Fanxiang M.2 NVME SSD 1To 2To 4To PS5 avec Dissipateur", 50), learned))
        assertTrue(SavedSearchMonitor.beatsTheBestSeen(listing("Samsung 990 PRO 2TB (2000GB) Nur 5GB geschrieben", 120), learned))
        assertTrue(SavedSearchMonitor.beatsTheBestSeen(listing("M.2 SSD 2TB (2x 1TB) NVMe", 120), learned))
    }

    @Test
    fun `an eBay offer whose condition was not read neither sets nor beats a learned price`() {
        val learned = sf().copy(learnUntil = Instant.fromEpochMilliseconds(0), bestSeenEur = 71)
        val es = io.github.tieo.arbay.model.PlatformId.EBAY_ES
        // The SN730 eBay.de calls for parts, as its eBay.es copy came in.
        assertFalse(SavedSearchMonitor.pricesTheThing(listing("Western Digital SN730 1TB SSD NVME", 50, platform = es)))
        assertFalse(SavedSearchMonitor.beatsTheBestSeen(listing("Western Digital SN730 1TB SSD NVME", 50, platform = es), learned))
        assertTrue(SavedSearchMonitor.beatsTheBestSeen(listing("Western Digital SN730 1TB SSD NVME", 50, Condition.USED, es), learned))
        // A Kleinanzeigen seller need not tick a condition, so none there is no sign of a misread.
        assertTrue(SavedSearchMonitor.pricesTheThing(listing("Sabrent Rocket 1TB SSD", 45)))
    }

    @Test
    fun `a rule learns until its date and tells after it`() {
        val rule = sf().copy(learnUntil = Instant.fromEpochMilliseconds(1_000))
        assertTrue(rule.learning(Instant.fromEpochMilliseconds(999)))
        assertFalse(rule.learning(Instant.fromEpochMilliseconds(1_000)))
        assertFalse(sf().learning(Instant.fromEpochMilliseconds(0)))
    }
}
