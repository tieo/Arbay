package io.github.tieo.arbay.chat

import io.github.tieo.arbay.chat.Kleinanzeigen.toArbay
import io.github.tieo.arbay.neuland.MarketAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.serialization.json.Json

class KleinanzeigenTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** A conversation as Neuland answers it (its records' field names), buying, with a message of
     *  each author and two of the site's notices, one of the buyer's act and one of the seller's. */
    private val thread = json.decodeFromString<MarketAccount.Thread>(
        """
        {"conversation": {"id": "5p1:ab:cd", "listing": "3210987654", "title": "Pixel 9 Pro XL", "image": null,
          "side": "buying", "partner": "Erika", "unread": 2, "latest_at": "2026-09-25T14:21:39.123+02:00", "latest": "Ja"},
         "asking": {"cents": 40000, "currency": "EUR"},
         "messages": [
           {"id": "m1", "author": "me", "text": "Noch da?", "at": "2026-09-25T13:00:00.000+02:00", "pictures": [], "offer": null, "notice": null},
           {"id": "m2", "author": "them", "text": "Ja", "at": "2026-09-25T14:21:39.123+02:00", "pictures": ["https://img/1"], "offer": null, "notice": null},
           {"id": "o1", "author": "site", "text": "Du hast eine Anfrage gesendet.", "at": "2026-09-25T15:00:00.000+02:00", "pictures": [],
            "offer": {"cents": 36000, "currency": "EUR"}, "notice": {"by": "buying", "event": "BUYER_OFFER_MADE"}},
           {"id": "o2", "author": "site", "text": "Der Verkäufer hat abgelehnt.", "at": "2026-09-25T16:00:00.000+02:00", "pictures": [],
            "offer": null, "notice": {"by": "selling", "event": "SELLER_REJECTED_OFFER"}}
         ]}
        """.trimIndent(),
    )

    @Test
    fun aNeulandConversationReadsAsArbays() {
        val c = thread.toArbay()
        assertEquals("KLEINANZEIGEN:3210987654", c.listingId)
        assertEquals("Erika", c.partner)
        assertEquals(true, c.buying)
        assertEquals(2, c.unread)
        assertEquals(Instant.parse("2026-09-25T12:21:39.123Z"), c.lastAt)
        assertEquals(listOf("m1", "m2", "o1", "o2"), c.messages.map { it.id })
        assertEquals(listOf("https://img/1"), c.messages[1].attachments)
    }

    @Test
    fun theSiteSNoticeIsTheUsersWhereItNamesTheUsersSide() {
        val mine = thread.toArbay().messages.associate { it.id to it.mine }
        assertEquals(mapOf("m1" to true, "m2" to false, "o1" to true, "o2" to false), mine)
        assertEquals(360.0, thread.toArbay().messages.first { it.id == "o1" }.offerEur)
        val selling = thread.copy(conversation = thread.conversation.copy(side = "selling")).toArbay()
        assertEquals(false, selling.buying)
        assertEquals(mapOf("o1" to false, "o2" to true), selling.messages.filter { it.id.startsWith("o") }.associate { it.id to it.mine })
    }
}
