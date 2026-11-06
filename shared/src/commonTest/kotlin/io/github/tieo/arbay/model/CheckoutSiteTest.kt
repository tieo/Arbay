package io.github.tieo.arbay.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckoutSiteTest {
    @Test
    fun onlyVintedItemsAndAlzaProductPagesAreBoughtAtTheirCheckout() {
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
    }
}
