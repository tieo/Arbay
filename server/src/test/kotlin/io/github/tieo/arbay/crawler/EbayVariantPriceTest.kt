package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.testing.offlineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A listing that sells several sizes at one address prints its cheapest and its dearest price on
 * the card ("EUR 17,99 a EUR 70,94"), as three s-card__price spans cut here from eBay.it.
 */
class EbayVariantPriceTest {

    private val card by lazy {
        EbayDeCrawler(offlineClient(), PlatformId.EBAY_IT, "ebay.it")
            .parseSearchResults(javaClass.getResource("/fixtures/ebay_it_variant_card_2026.html")!!.readText())
            .single()
    }

    @Test
    fun `a card with a span of prices keeps both ends`() {
        assertEquals(Money(1799, Currency.EUR), card.price)
        assertEquals(Money(7094, Currency.EUR), card.priceUpTo)
    }

    @Test
    fun `its cheapest choice is no price for the thing, so it is neither pushed nor learned`() {
        assertFalse(SavedSearchMonitor.isTheThing(card))
        assertFalse(SavedSearchMonitor.pricesTheThing(card))
    }

    @Test
    fun `a card with one price has no span`() {
        val italian = EbayDeCrawler(offlineClient(), PlatformId.EBAY_IT, "ebay.it")
            .parseSearchResults(javaClass.getResource("/fixtures/ebay_it_auctions_2026.html")!!.readText())
        italian.forEach { assertNull(it.priceUpTo, it.title) }
    }
}
