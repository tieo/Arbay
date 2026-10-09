package io.github.tieo.arbay

import io.github.tieo.arbay.model.BlockedDealer
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.DropReason
import io.github.tieo.arbay.model.DroppedListing
import io.github.tieo.arbay.model.Seller
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.results.HiddenKind
import io.github.tieo.arbay.results.Narrowing
import io.github.tieo.arbay.results.dealerThatCaught
import io.github.tieo.arbay.results.hiddenListings
import io.github.tieo.arbay.results.narrow
import io.github.tieo.arbay.results.withBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class NarrowingTest {
    private fun listing(id: String, euros: Long, condition: Condition? = null, saleType: SaleType? = null, sold: Boolean = false) =
        Listing(
            id = id, platformId = PlatformId.EBAY_DE, externalId = id, url = "https://example.invalid/$id",
            title = "Thing $id", price = Money(euros * 100, Currency.EUR), condition = condition,
            saleType = saleType, sold = sold, scrapedAt = Instant.fromEpochSeconds(0),
        )

    private val listings = listOf(
        listing("a", 100, Condition.USED, SaleType.FIXED_PRICE),
        listing("b", 200, Condition.NEW, SaleType.AUCTION),
        listing("c", 300, null, null),
        listing("d", 400, Condition.USED, SaleType.FIXED_PRICE, sold = true),
    )

    @Test
    fun `nothing narrowed shows everything for sale`() {
        val narrowed = narrow(listings, Narrowing(), emptySet())
        assertEquals(listOf("a", "b", "c"), narrowed.displayed.map { it.id })
        assertEquals(100f, narrowed.priceMin)
        assertEquals(300f, narrowed.priceMax)
        assertEquals(false, narrowed.priceFiltered)
    }

    @Test
    fun `a band keeps the listing it was narrowed onto`() {
        val narrowed = narrow(listings, Narrowing(band = 150f..200f), emptySet())
        assertEquals(listOf("b"), narrowed.displayed.map { it.id })
    }

    @Test
    fun `a band wider than the results is no narrowing`() {
        val narrowed = narrow(listings, Narrowing(band = 0f..Float.MAX_VALUE), emptySet())
        assertEquals(false, narrowed.priceFiltered)
        assertEquals(3, narrowed.displayed.size)
    }

    @Test
    fun `an unstated condition is its own answer`() {
        val usedOnly = narrow(listings, Narrowing(conditions = setOf(Condition.USED), unstatedCondition = false), emptySet())
        assertEquals(listOf("a"), usedOnly.displayed.map { it.id })
        val usedOrUnstated = narrow(listings, Narrowing(conditions = setOf(Condition.USED), unstatedCondition = true), emptySet())
        assertEquals(listOf("a", "c"), usedOrUnstated.displayed.map { it.id })
    }

    @Test
    fun `new only shows the backlog`() {
        val narrowed = narrow(listings, Narrowing(newOnly = true), setOf("b"))
        assertEquals(listOf("b"), narrowed.displayed.map { it.id })
    }

    @Test
    fun `every hidden listing is in a group that says why`() {
        val narrowing = Narrowing(band = 150f..300f, conditions = setOf(Condition.NEW), unstatedCondition = false)
        val narrowed = narrow(listings, narrowing, emptySet())
        val hidden = hiddenListings(narrowed, narrowing, listings, listings, emptySet(), emptyList(), emptyList(), emptySet())
        assertEquals(listOf(HiddenKind.PriceBand, HiddenKind.Condition), hidden.map { it.kind })
        assertEquals(listOf("a"), hidden[0].listings.map { it.id })
        assertEquals(listOf("c"), hidden[1].listings.map { it.id })
    }

    @Test
    fun `a blocked dealer's offers are one group, whether the server or this screen took them`() {
        val dealer = Seller(name = "Beispiel Nutzfahrzeuge GmbH", id = "900001")
        val onScreen = listing("e", 500).copy(seller = dealer)
        val dropped = listing("f", 600).copy(seller = dealer)
        val fetched = listings + onScreen
        val blocked = listOf(BlockedDealer(PlatformId.EBAY_DE, "900001", "Beispiel Nutzfahrzeuge GmbH", Instant.fromEpochSeconds(0)))
        val basis = fetched.filter { it.id != "e" }
        val narrowed = narrow(basis, Narrowing(), emptySet())
        val hidden = hiddenListings(
            narrowed, Narrowing(), fetched, basis, emptySet(), emptyList(),
            listOf(DroppedListing(dropped, DropReason.BLOCKED_DEALER, "Beispiel Nutzfahrzeuge GmbH")), emptySet(),
            blockedDealers = blocked,
        )
        assertEquals(listOf(HiddenKind.BlockedDealers), hidden.map { it.kind })
        assertEquals(listOf("e", "f"), hidden.single().listings.map { it.id })
        assertEquals("Sold by Beispiel Nutzfahrzeuge GmbH.", hidden.single().why)
        assertEquals(blocked.single(), dealerThatCaught(dropped, blocked))
    }

    @Test
    fun `a band resting on the track's end is saved as no bound`() {
        val narrowed = narrow(listings, Narrowing(), emptySet())
        val saved = SearchQuery(text = "thing", category = MarketGroup.GENERAL).withBand(150f..300f, narrowed)
        assertEquals(15_000L, saved.minPrice?.amount)
        assertNull(saved.maxPrice)
    }

    @Test
    fun `a saved search seeds the narrowing it was left at`() {
        val saved = SearchQuery(text = "thing", category = MarketGroup.GENERAL, condition = listOf(Condition.NEW), conditionUnstated = false)
        val seeded = Narrowing.of(saved)
        assertEquals(setOf(Condition.NEW), seeded.conditions)
        assertEquals(false, seeded.unstatedCondition)
        assertNull(seeded.band)
    }
}
