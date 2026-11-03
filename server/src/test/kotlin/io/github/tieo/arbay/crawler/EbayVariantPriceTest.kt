package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * A listing that sells several sizes at one address prints its cheapest and its dearest price on
 * the card ("EUR 17,99 a EUR 70,94" on eBay.it); Neuland reads both ends.
 */
class EbayVariantPriceTest {

    private val card = Listing(
        id = "EBAY_IT:136209271626", platformId = PlatformId.EBAY_IT, externalId = "136209271626",
        url = "https://www.ebay.it/itm/136209271626",
        title = "Kingchuxing SSD SATA3 mSATA 128 GB 256 GB 512 GB unità a stato solido interna",
        price = Money(1799, Currency.EUR), priceUpTo = Money(7094, Currency.EUR),
        scrapedAt = kotlin.time.Instant.fromEpochSeconds(0),
    )

    @Test
    fun `its cheapest choice is no price for the thing, so it is neither pushed nor learned`() {
        assertFalse(SavedSearchMonitor.isTheThing(card))
        assertFalse(SavedSearchMonitor.pricesTheThing(card))
    }
}
