package io.github.tieo.arbay.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteTest {
    @Test
    fun anAdForSaleHasAnAddressOfItsOwn() {
        assertEquals("/sell/d1792", Route.Sell("d1792").path())
        assertEquals(Route.Sell("d1792"), Route.parse("/sell/d1792"))
        assertEquals(Route.Inbox, Route.parse("/sell"))
    }
}
