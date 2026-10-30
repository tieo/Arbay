package io.github.tieo.arbay

import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SignInLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignInLinkTest {
    @Test
    fun aLinkReadsBackAsTheSignInItAsksFor() {
        val link = SignInLink(PlatformId.VINTED_DE, payment = true)
        assertEquals("arbay://open/settings?signin=VINTED_DE&payment=true", link.appLink())
        assertEquals(link, SignInLink.parse(link.appLink().substringAfter('?')))
        assertEquals(link, SignInLink.parse("?signin=VINTED_DE&payment=true"))
        assertEquals(SignInLink(PlatformId.EBAY_DE), SignInLink.parse("signin=EBAY_DE"))
    }

    @Test
    fun anAddressWithoutASignInAsksNone() {
        assertNull(SignInLink.parse(""))
        assertNull(SignInLink.parse("?panel=prices"))
        assertNull(SignInLink.parse("signin=NO_SUCH_MARKET"))
    }
}
