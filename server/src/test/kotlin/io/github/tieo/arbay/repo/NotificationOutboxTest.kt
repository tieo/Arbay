package io.github.tieo.arbay.repo

import io.github.tieo.arbay.model.SubfilterMatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationOutboxTest {
    private fun match(id: String) = SubfilterMatch(
        listingId = id, searchName = "search", subfilterName = "cheap", title = "thing $id", url = "https://example.com/$id",
    )

    @Test
    fun `a delivery that never arrived is sent again until the phone says it was shown`() {
        NotificationOutbox.takeAll()
        NotificationOutbox.hold(listOf(match("a")), emptyList())

        val first = NotificationOutbox.afterAcknowledging(0)
        assertEquals(listOf("a"), first.subfilterMatches.map { it.listingId })

        // The answer was lost: the phone still acknowledges only what it had before.
        val again = NotificationOutbox.afterAcknowledging(0)
        assertEquals(listOf("a"), again.subfilterMatches.map { it.listingId })

        // Shown now, and acknowledged on the next poll.
        NotificationOutbox.hold(listOf(match("b")), emptyList())
        val next = NotificationOutbox.afterAcknowledging(again.upTo)
        assertEquals(listOf("b"), next.subfilterMatches.map { it.listingId })
        assertTrue(next.upTo > again.upTo)
    }

    @Test
    fun `an app that does not acknowledge is served once, as before`() {
        NotificationOutbox.takeAll()
        NotificationOutbox.hold(listOf(match("c")), emptyList())
        assertEquals(1, NotificationOutbox.takeAll().subfilterMatches.size)
        assertEquals(0, NotificationOutbox.takeAll().subfilterMatches.size)
    }
}
