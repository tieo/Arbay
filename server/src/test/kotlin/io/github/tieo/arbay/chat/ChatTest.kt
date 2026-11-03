package io.github.tieo.arbay.chat

import io.github.tieo.arbay.judging.KleinanzeigenCosts
import io.github.tieo.arbay.model.fillIn
import io.github.tieo.arbay.model.offerWithin
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatTest {
    /** The fee as Kleinanzeigen's help article states it in the saved fixture. */
    /** "Servicegebühr von 0,50 € + 4,5 % des Kaufpreises", as the help article stated it on 2026-10-08. */
    private val protection = io.github.tieo.arbay.model.BuyerProtection(fixedEur = 0.5, share = 0.045)

    @Test
    fun offerKeepsPriceShippingAndFeeWithinTheLimit() {
        val offer = offerWithin(450.0, 4.99, protection)
        assertEquals(425, offer)
        // What the buyer pays at that price stays at or under the limit, and one euro more goes over.
        fun paid(p: Int) = p + 4.99 + 0.50 + p * 0.045
        assert(paid(offer) <= 450.0)
        assert(paid(offer + 1) > 450.0)
    }

    @Test
    fun withoutProtectionOnlyShippingComesOff() {
        assertEquals(445, offerWithin(450.0, 5.0, null))
    }

    @Test
    fun fillInsCompleteOnlyWhatIsKnown() {
        assertEquals("425 € für Pixel, Versand {versand}", fillIn("{preis} € für {titel}, Versand {versand}", 425, "Pixel", null))
    }

    @Test
    fun theDirectPriceFillsIn() {
        assertEquals("425 oder 450", fillIn("{preis} oder {preis_direkt}", 425, "Pixel", null, 450))
    }

    @Test
    fun aFillInCanAddToThePrice() {
        assertEquals("428 oder 448", fillIn("{preis} oder {preis+20}", 428, "Pixel", null))
        assertEquals("{preis+20}", fillIn("{preis+20}", null, "Pixel", null))
    }
}
