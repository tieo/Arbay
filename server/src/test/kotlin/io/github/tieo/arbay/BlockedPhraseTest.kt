package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.model.*
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockedPhraseTest {
    private fun listing(title: String) = Listing(
        id = "test:${title.hashCode()}", platformId = PlatformId.EBAY_DE, externalId = title.hashCode().toString(),
        url = "https://example.com", title = title, price = Money(10000, Currency.EUR), scrapedAt = Clock.System.now(),
    )

    private val asked = RelevanceFilter.parseQuery(SearchQuery(
        text = "2TB NVMe", aliases = listOf("2 TB NVMe"), excludeKeywords = listOf("2,5 zoll", "2.5 zoll"), category = MarketGroup.GENERAL,
    ))

    @Test
    fun `a blocked measure blocks the measure, not every 2`() {
        // The SSD search blocks 2.5 inch SATA drives; "2 TB" and "2TB" are the size it asks for.
        assertNull(RelevanceFilter.blockedWord(listing("Samsung 980 PRO 2 TB NVMe M.2"), asked))
        assertNull(RelevanceFilter.blockedWord(listing("Kingston NV2 2TB M.2 NVMe PCIe 4.0"), asked))
        assertEquals("2p5zoll", RelevanceFilter.blockedWord(listing("Crucial MX500 2TB SSD 2,5 Zoll SATA"), asked))
        assertEquals("2p5zoll", RelevanceFilter.blockedWord(listing("Samsung 870 EVO 2TB 2.5 Zoll"), asked))
    }

    @Test
    fun `a blocked phrase sent to the server stays one phrase`() {
        // Sent comma-joined, "2,5 zoll" came apart into "2" and "5 zoll".
        val params = io.ktor.http.parametersOf("exclude" to listOf("2,5 zoll", "adapter"))
        assertEquals(listOf("2,5 zoll", "adapter"), params.getAll("exclude"))
    }

    @Test
    fun `a one euro ask among real prices is a placeholder, not the cheapest`() {
        fun priced(title: String, eur: Long) = listing(title).copy(id = "t:$title", price = Money(eur * 100, Currency.EUR))
        val result = RelevanceFilter.partition(listOf(
            priced("PNY XLR8 CS3030 2TB NVMe SSD", 1),
            priced("Samsung 980 PRO 2TB NVMe", 120),
            priced("Kingston NV3 2TB NVMe", 110),
        ), SearchQuery(text = "2TB NVMe", category = MarketGroup.GENERAL), emptyList())
        assertEquals(listOf("Samsung 980 PRO 2TB NVMe", "Kingston NV3 2TB NVMe").sorted(), result.kept.map { it.title }.sorted())
        assertEquals(DropReason.NO_REAL_PRICE, result.dropped.single().reason)
    }
}
