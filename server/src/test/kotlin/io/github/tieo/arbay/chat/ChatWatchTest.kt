package io.github.tieo.arbay.chat

import io.github.tieo.arbay.model.ChatMessage
import io.github.tieo.arbay.model.Conversation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatWatchTest {
    private fun msg(id: String, mine: Boolean, text: String = id) = ChatMessage(id = id, mine = mine, text = text)

    // A buyer asks twice, the user answers, the buyer writes twice more: two unread.
    private val pixel = Conversation(
        id = "c1", adTitle = "Pixel 9 Pro XL", partner = "Anna", buying = false, unread = 2,
        messages = listOf(msg("a", false), msg("b", false), msg("me", true), msg("c", false), msg("d", false)),
    )

    @Test
    fun `the unread messages are the other side's last ones, each reported once`() {
        assertEquals(listOf("c", "d"), unreadFromThem(pixel, emptySet()).map { it.id })
        assertEquals(listOf("d"), unreadFromThem(pixel, setOf("c")).map { it.id })
        assertEquals(emptyList(), unreadFromThem(pixel.copy(unread = 0), emptySet()).map { it.id })
    }

    @Test
    fun `the user's own messages are never news`() {
        val answered = pixel.copy(unread = 1, messages = pixel.messages + msg("me2", true))
        assertEquals(listOf("d"), unreadFromThem(answered, emptySet()).map { it.id })
    }

    private fun incoming(conversation: String, partner: String, ad: String, text: String, seq: Long) =
        IncomingMessage(seq, conversation, "m$seq", partner, ad, null, buying = false, text = text)

    @Test
    fun `one push quotes who wrote about which ad, one conversation or several`() {
        val one = listOf(incoming("c1", "Anna", "Pixel 9 Pro XL", "Kann ich heute abholen?", 1))
        assertEquals("Anna · Pixel 9 Pro XL", pushTitle(one))
        assertEquals("Kann ich heute abholen?", pushBody(one))
        val two = one + incoming("c2", "Ben", "Pixel 9 Pro XL", "Noch da?\n\nGruß", 2)
        assertEquals("2 new messages on Kleinanzeigen", pushTitle(two))
        val body = pushBody(two)
        assertTrue("Anna (Pixel 9 Pro XL): Kann ich heute abholen?" in body, body)
        assertTrue("Ben (Pixel 9 Pro XL): Noch da? Gruß" in body, body)
    }
}
