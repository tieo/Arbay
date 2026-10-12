package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking

class PaginateTest {

    private fun page(n: Int, size: Int = 25) = (1..size).map { i ->
        val id = "${n}_$i"
        Listing(
            id = "KLEINANZEIGEN:$id", platformId = PlatformId.KLEINANZEIGEN, externalId = id,
            url = "https://example.com/$id", title = "Offer $id", price = Money(1000, Currency.EUR),
            scrapedAt = Clock.System.now(),
        )
    }

    private fun read(query: SearchQuery, pagesOnMarket: Int): Pair<Int, Int> = runBlocking {
        var asked = 0
        val found = paginate(query) { n -> asked++; if (n <= pagesOnMarket) page(n) else emptyList() }
        found.size to asked
    }

    @Test
    fun `a usual search stops at the configured number of offers`() {
        val (found, _) = read(SearchQuery(text = "2TB NVMe", category = MarketGroup.GENERAL), pagesOnMarket = 40)
        val cap = CrawlerConfig.current.maxResultsPerPlatform
        assertEquals((cap + 24) / 25 * 25, found)
    }

    @Test
    fun `a search asking for more reads past the usual pages until it has them`() {
        val (found, asked) = read(SearchQuery(text = "2TB NVMe", category = MarketGroup.GENERAL, maxResults = 500), pagesOnMarket = 40)
        assertEquals(500, found)
        assertEquals(20, asked)
    }

    @Test
    fun `a search asking for more stops where the market runs dry`() {
        val (found, asked) = read(SearchQuery(text = "2TB NVMe", category = MarketGroup.GENERAL, maxResults = 1000), pagesOnMarket = 12)
        assertEquals(300, found)
        assertEquals(13, asked)
    }
}
