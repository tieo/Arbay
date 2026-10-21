package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SaleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class PriceDropsTest {
    private fun van(id: String, eur: Long, currency: Currency = Currency.EUR, saleType: SaleType? = null) = Listing(
        id = "MOBILE_DE:$id", platformId = PlatformId.MOBILE_DE, externalId = id, url = "https://example.invalid/$id",
        title = "Volkswagen Crafter $id", price = Money(eur * 100, currency), scrapedAt = Clock.System.now(), saleType = saleType,
    )

    /** The landed price in EUR as the test states it: the listing's own amount, one to one. */
    private val eur: (Listing) -> Long? = { it.effectivePrice.amount }

    @Test
    fun `an offer found again for less is a drop from its price before the first cut until the search is opened`() {
        val search = "price-drops-test-${System.nanoTime()}"
        val t = Clock.System.now()
        // First sighting only remembers the price.
        assertEquals(emptyList(), PriceDrops.update(search, listOf(van("a", 25_000), van("b", 20_000)), t, eur))
        // Same price, and a raise, are no drop.
        assertEquals(emptyList(), PriceDrops.update(search, listOf(van("a", 25_000), van("b", 21_000)), t + 1.hours, eur))
        val first = PriceDrops.update(search, listOf(van("a", 23_500), van("b", 21_000)), t + 2.hours, eur).single()
        assertEquals("MOBILE_DE:a", first.listingId); assertEquals(25_000.0, first.fromEur); assertEquals(23_500.0, first.toEur)
        // Cut again: still from the price before the first cut.
        PriceDrops.update(search, listOf(van("a", 22_000)), t + 3.hours, eur)
        assertEquals(listOf(25_000.0 to 22_000.0), PriceDrops.of(search).map { it.fromEur to it.toEur })
        // Raised but still below where it started: the drop stays at the price it has now.
        PriceDrops.update(search, listOf(van("a", 24_000)), t + 4.hours, eur)
        assertEquals(listOf(25_000.0 to 24_000.0), PriceDrops.of(search).map { it.fromEur to it.toEur })
        // Back up to where it started: no drop at all.
        PriceDrops.update(search, listOf(van("a", 25_000)), t + 5.hours, eur)
        assertEquals(emptyList(), PriceDrops.of(search))
        // Opening clears the drops and keeps the prices, so the next cut counts from the last one.
        PriceDrops.update(search, listOf(van("a", 24_000)), t + 6.hours, eur)
        PriceDrops.opened(search)
        assertEquals(emptyList(), PriceDrops.of(search))
        assertEquals(listOf(24_000.0 to 23_000.0), PriceDrops.update(search, listOf(van("a", 23_000)), t + 7.hours, eur).map { it.fromEur to it.toEur })
    }

    @Test
    fun `a bid going nowhere, a new currency, and an offer a market skipped for a run make no drop`() {
        val search = "price-drops-test-${System.nanoTime()}"
        val t = Clock.System.now()
        PriceDrops.update(search, listOf(van("auction", 5_000, saleType = SaleType.AUCTION), van("chf", 30_000, Currency.CHF), van("away", 20_000)), t, eur)
        // A run that did not find "away" keeps its price for the next one.
        PriceDrops.update(search, listOf(van("chf", 30_000, Currency.CHF)), t + 1.hours, eur)
        val cut = PriceDrops.update(
            search,
            listOf(van("auction", 4_000, saleType = SaleType.AUCTION), van("chf", 29_000, Currency.EUR), van("away", 19_000)),
            t + 2.hours, eur,
        )
        assertEquals(listOf("MOBILE_DE:away"), cut.map { it.listingId })
    }
}
