package io.github.tieo.arbay

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.DropCount
import io.github.tieo.arbay.model.DropReason
import io.github.tieo.arbay.model.DroppedListing
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.dropCounts
import io.github.tieo.arbay.model.sampleDropped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DroppedSampleTest {
    private fun dropped(id: String, reason: DropReason, detail: String? = null) = DroppedListing(
        Listing(
            id = id,
            platformId = PlatformId.AUTOSCOUT24,
            externalId = id,
            url = "https://example.invalid/$id",
            title = "Volkswagen Crafter $id",
            price = Money(2_000_000, Currency.EUR),
            scrapedAt = Instant.fromEpochSeconds(0),
        ),
        reason, detail,
    )

    @Test
    fun `a reason that removed hundreds leaves room for every other reason`() {
        // AutoScout24 on the Crafter search: 78 blocked words ranked first by the market, then 452
        // vans removed for their mileage or year. A plain first-80 sent the words and nothing else.
        val all = List(78) { dropped("w$it", DropReason.BLOCKED_WORD) } +
            List(2) { dropped("d$it", DropReason.BLOCKED_DEALER, "Automarkt Stuttgart") } +
            List(300) { dropped("m$it", DropReason.VEHICLE_CRITERIA, "mileage") } +
            List(152) { dropped("y$it", DropReason.VEHICLE_CRITERIA, "year") }
        val sent = sampleDropped(all, 80)
        assertEquals(80, sent.size)
        assertEquals(setOf("w", "d", "m", "y"), sent.map { it.listing.id.take(1) }.toSet())
        // Still in the order the market sent them.
        assertEquals(sent.sortedBy { all.indexOf(it) }, sent)
        assertEquals(
            listOf(
                DropCount(DropReason.VEHICLE_CRITERIA, "mileage", 300),
                DropCount(DropReason.VEHICLE_CRITERIA, "year", 152),
                DropCount(DropReason.BLOCKED_WORD, null, 78),
                DropCount(DropReason.BLOCKED_DEALER, "Automarkt Stuttgart", 2),
            ),
            all.dropCounts(),
        )
    }

    @Test
    fun `a few removed offers are all sent`() {
        val all = List(5) { dropped("w$it", DropReason.BLOCKED_WORD) }
        assertEquals(all, sampleDropped(all, 80))
    }
}
