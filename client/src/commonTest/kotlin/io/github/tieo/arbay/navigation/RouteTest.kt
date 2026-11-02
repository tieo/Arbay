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

    @Test
    fun aSignInFormKeepsItsScreen() {
        assertEquals(Route.SignIn("google.com", "a@b.de"), Route.parse("/sign-in/google.com/a@b.de"))
        assertEquals(true, Route.SignIn("google.com").keepsTheScreen)
        assertEquals(false, Route.parse("/search/ssd").keepsTheScreen)
    }
}
