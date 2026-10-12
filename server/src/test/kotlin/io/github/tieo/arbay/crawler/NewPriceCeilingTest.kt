package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

class NewPriceCeilingTest {
    private fun offer(platform: PlatformId, euro: Double, title: String = "Drive 2TB") = Listing(
        id = "${platform.name}:$euro", platformId = platform, externalId = "$euro", url = "https://x/$euro",
        title = title, price = Money((euro * 100).toLong(), Currency.EUR), scrapedAt = Clock.System.now(),
    )

    @Test
    fun `the new price is the third cheapest the comparers list, so one stray accessory cannot set it`() {
        val listed = listOf(
            offer(PlatformId.GEIZHALS, 12.0, "Kühlkörper 2TB"),
            offer(PlatformId.GEIZHALS, 229.90), offer(PlatformId.GEIZHALS, 230.90),
            offer(PlatformId.IDEALO, 266.0),
        )
        assertEquals(23090, NewPriceCeiling.of(listed)?.amount)
    }

    @Test
    fun `fewer comparer offers than that is no price`() {
        assertNull(NewPriceCeiling.of(listOf(offer(PlatformId.GEIZHALS, 229.90), offer(PlatformId.IDEALO, 266.0))))
    }

    @Test
    fun `a search with no comparer, or nothing but comparers, has no ceiling`() {
        assertNull(NewPriceCeiling.forSearch(listOf(PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE)))
        assertNull(NewPriceCeiling.forSearch(listOf(PlatformId.GEIZHALS, PlatformId.IDEALO)))
    }

    @Test
    fun `the others wait until every comparer has answered, a failed one included`() = runBlocking {
        val ceiling = NewPriceCeiling.forSearch(listOf(PlatformId.GEIZHALS, PlatformId.IDEALO, PlatformId.KLEINANZEIGEN))!!
        val waiting = async { ceiling.await() }
        ceiling.answered(PlatformId.GEIZHALS, listOf(129.9, 141.99, 154.99, 157.34).map { offer(PlatformId.GEIZHALS, it) })
        ceiling.answered(PlatformId.KLEINANZEIGEN, listOf(offer(PlatformId.KLEINANZEIGEN, 1.0)))
        yield()
        assertFalse(waiting.isCompleted)
        ceiling.answered(PlatformId.IDEALO, emptyList())
        assertEquals(15499, waiting.await()?.amount)
        // A second report from a comparer changes nothing.
        ceiling.answered(PlatformId.GEIZHALS, emptyList())
        assertTrue(waiting.isCompleted)
    }
}
