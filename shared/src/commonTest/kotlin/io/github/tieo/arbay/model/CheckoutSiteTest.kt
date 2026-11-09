package io.github.tieo.arbay.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckoutSiteTest {
    @Test
    fun onlyVintedItemsEbayItemPagesAndAlzaProductPagesAreBoughtAtTheirCheckout() {
        assertEquals("ebay.de", checkoutSite("https://www.ebay.de/itm/198772096981"))
        assertEquals("ebay.de", checkoutSite("https://www.ebay.de/itm/198772096981?hash=item2e"))
        assertNull(checkoutSite("https://www.ebay.de/sch/i.html?_nkw=ssd"))
        assertNull(checkoutSite("https://www.ebay.de/itm/titel/198772096981"))
        assertNull(checkoutSite("https://www.ebay.de.evil.example/itm/198772096981"))
        assertNull(checkoutSite("http://www.ebay.de/itm/198772096981"))
        assertNull(checkoutSite("https://www.ebay.com/itm/198772096981"))
        assertEquals("vinted.de", checkoutSite("https://www.vinted.de/items/123-ein-buch"))
        assertEquals("alza.de", checkoutSite("https://www.alza.de/cudy-8-port-gigabit-desktop-switch-with-usb-c-d13100143.htm"))
        assertEquals("alza.de", checkoutSite("https://www.alza.de/cudy-switch-d13100143.htm?o=1"))
        assertNull(checkoutSite("https://www.alza.de/Order1.htm"))
        assertNull(checkoutSite("https://www.alza.de/search.htm?exps=cudy"))
        assertNull(checkoutSite("https://www.alza.de.evil.example/x-d1.htm"))
        assertNull(checkoutSite("http://www.alza.de/x-d1.htm"))
        assertNull(checkoutSite("https://www.vinted.de/member/1"))
        assertNull(checkoutSite("https://www.kleinanzeigen.de/s-anzeige/x/1"))
        assertEquals("alza", checkoutShop("alza.de"))
        assertEquals("eBay", checkoutShop("ebay.de"))
    }

    private fun offer(url: String, sale: SaleType?) =
        Listing(id = "1", platformId = PlatformId.EBAY_DE, externalId = "1", url = url, title = "t", price = Money(100, Currency.EUR), scrapedAt = kotlin.time.Instant.fromEpochMilliseconds(0), saleType = sale)

    @Test
    fun eBayBuysOnlyWhatIsKnownToBeAtAFixedPrice() {
        val item = "https://www.ebay.de/itm/198772096981"
        assertEquals("ebay.de", checkoutSite(offer(item, SaleType.FIXED_PRICE)))
        assertNull(checkoutSite(offer(item, SaleType.AUCTION)))
        assertNull(checkoutSite(offer(item, SaleType.RENT)))
        assertNull(checkoutSite(offer(item, null)))
    }

    @Test
    fun aVintedOfferWithoutASaleTypeStaysBuyableAndAnAuctionNever() {
        val item = "https://www.vinted.de/items/123-ein-buch"
        assertEquals("vinted.de", checkoutSite(offer(item, null)))
        assertNull(checkoutSite(offer(item, SaleType.AUCTION)))
    }
}
