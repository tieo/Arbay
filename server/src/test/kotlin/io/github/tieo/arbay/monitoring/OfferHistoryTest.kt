package io.github.tieo.arbay.monitoring

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.monitoring.OfferHistory.Event
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json

class OfferHistoryTest {
    private val now = Instant.parse("2026-11-10T00:00:00Z")

    private fun offer(id: String, cents: Long) = Listing(
        id = "KLEINANZEIGEN:$id", platformId = PlatformId.KLEINANZEIGEN, externalId = id, url = "https://example.test/$id",
        title = "Offer $id", price = Money(cents, Currency.EUR), scrapedAt = Clock.System.now(),
    )

    private fun File.events() = readLines().map { Json.decodeFromString(OfferHistory.Entry.serializer(), it).event }

    private fun log() = File.createTempFile("history", ".jsonl").also { it.delete(); it.deleteOnExit() }

    @Test
    fun `an offer's life is written once per change`() {
        val f = log()
        val h = OfferHistory(f)
        h.update("s", listOf(offer("1", 10000)), emptySet(), now)
        h.update("s", listOf(offer("1", 10000)), emptySet(), now)
        h.update("s", listOf(offer("1", 9000)), emptySet(), now)
        h.update("s", emptyList(), emptySet(), now)
        h.update("s", emptyList(), emptySet(), now)
        h.update("s", listOf(offer("1", 9000)), emptySet(), now)
        h.ended("s", "KLEINANZEIGEN:1", "DELETED", now)
        assertEquals(listOf(Event.FIRST_SEEN, Event.PRICE_CHANGED, Event.NOT_KEPT, Event.KEPT_AGAIN, Event.AD_ENDED), f.events())
    }

    @Test
    fun `an offer is not missing from a run its market did not answer`() {
        val f = log()
        val h = OfferHistory(f)
        h.update("s", listOf(offer("1", 10000)), emptySet(), now)
        h.update("s", emptyList(), setOf("KLEINANZEIGEN"), now)
        assertEquals(listOf(Event.FIRST_SEEN), f.events())
    }

    @Test
    fun `a restart goes on from what the file says`() {
        val f = log()
        OfferHistory(f).update("s", listOf(offer("1", 10000)), emptySet(), now)
        val again = OfferHistory(f)
        again.update("s", listOf(offer("1", 10000)), emptySet(), now)
        assertEquals(listOf(Event.FIRST_SEEN), f.events())
        again.update("s", listOf(offer("1", 8000)), emptySet(), now)
        assertEquals(listOf(Event.FIRST_SEEN, Event.PRICE_CHANGED), f.events())
    }
}
