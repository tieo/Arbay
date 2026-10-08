package io.github.tieo.arbay.chat

import io.github.tieo.arbay.crawler.KleinanzeigenCosts
import io.github.tieo.arbay.model.fillIn
import io.github.tieo.arbay.model.directPrice
import io.github.tieo.arbay.model.offerWithin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ChatTest {
    /** The fee as Kleinanzeigen's help article states it in the saved fixture. */
    private val protection = KleinanzeigenCosts.parseProtection(javaClass.getResource("/fixtures/kleinanzeigen_help_sicher_bezahlen.json")!!.readText())

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
    fun theDirectPriceIsWhatTheProtectedOneCostsInAll() {
        // 430 € through Sicher bezahlen costs 430 + 0.50 + 19.35 = 449.85 €.
        assertEquals(449, directPrice(430, 0.0, protection))
        assertEquals(418, directPrice(400, 0.0, protection))
        assertEquals("430 oder 449", fillIn("{preis} oder {preis_direkt}", 430, "Pixel", null, directPrice(430, 0.0, protection)))
    }

    @Test
    fun aFillInCanAddToThePrice() {
        assertEquals("428 oder 448", fillIn("{preis} oder {preis+20}", 428, "Pixel", null))
        assertEquals("{preis+20}", fillIn("{preis+20}", null, "Pixel", null))
    }

    @Test
    fun gatewayConversationReadsAsArbayConversation() {
        val raw = Json.parseToJsonElement(
            """
            {"id":"5p1:ab:cd","adId":"3210987654","adTitle":"Pixel 9 Pro XL","role":"BUYER","sellerName":"Erika",
             "buyerName":"Me","unreadMessagesCount":2,"receivedDate":"2026-09-25T14:21:39.123+0200",
             "messages":[
               {"messageId":"m2","boundness":"INBOUND","textShort":"Ja","receivedDate":"2026-09-25T14:21:39.123+0200"},
               {"messageId":"m1","boundness":"OUTBOUND","textShort":"Noch da?","receivedDate":"2026-09-25T13:00:00.000+0200"},
               {"messageId":"m0","boundness":"INBOUND","textShort":"","receivedDate":"2026-09-25T12:59:59.000+0200"}
             ]}
            """.trimIndent(),
        ).jsonObject
        val c = assertNotNull(raw.toConversation(withMessages = true))
        assertEquals("KLEINANZEIGEN:3210987654", c.listingId)
        assertEquals("Erika", c.partner)
        assertEquals(2, c.unread)
        assertEquals(listOf("m1", "m2"), c.messages.map { it.id })
        assertEquals(true, c.messages.first().mine)
        assertEquals("2026-09-25T12:21:39.123Z", c.lastAt.toString())
    }
}
