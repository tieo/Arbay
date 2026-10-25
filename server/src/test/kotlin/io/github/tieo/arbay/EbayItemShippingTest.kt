package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.ebayCoupon
import io.github.tieo.arbay.crawler.ebayItemShipping
import io.github.tieo.arbay.crawler.ebaySellerNotes
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EbayItemShippingTest {
    // RaidSonic's own B-Ware listing of the ICY BOX IB-3740-C31, 2026-10-25: the search card carried
    // no shipping line, and the item page's data holds DHL at 5,95 € and free collection.
    private val page = javaClass.getResource("/fixtures/ebay_item_shipping_notes.html")!!.readText()

    @Test
    fun `an item page's shipping is the cheapest service reaching Germany`() {
        val shipping = ebayItemShipping(page)!!
        assertEquals(Money(595, Currency.EUR), shipping.cost)
        assertFalse(shipping.free)
        assertTrue(shipping.pickup)
        assertTrue(shipping.available)
    }

    @Test
    fun `the seller's condition note is read whole`() {
        assertEquals(
            "Kundenretoure Zustand: Gebrauchsspuren können Kratzer vorhanden sein - Zubehör: vollständig, Eventuell keine OVP",
            ebaySellerNotes(page),
        )
    }

    @Test
    fun `a coupon code's price is read with the code`() {
        val coupon = ebayCoupon(page)!!
        assertEquals(Money(12000, Currency.EUR), coupon.price)
        assertEquals("PERFECTEBAY4", coupon.code)
        assertTrue(coupon.terms!!.contains("26. Oct."), coupon.terms)
    }

    @Test
    fun `a page without the data says nothing`() {
        assertNull(ebayItemShipping("<html>\"shippingOptions\":\"Versandoptionen\"</html>"))
        assertNull(ebaySellerNotes("<html></html>"))
        assertNull(ebayCoupon("<html></html>"))
    }
}
