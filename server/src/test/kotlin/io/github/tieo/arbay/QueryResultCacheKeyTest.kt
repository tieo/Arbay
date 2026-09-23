package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SortMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QueryResultCacheKeyTest {
    private val base = SearchQuery(text = "vw crafter", category = MarketGroup.VEHICLES, carFilters = CarFilters())
    private fun key(q: SearchQuery) = QueryResultCache.key(PlatformId.KLEINANZEIGEN, q)

    @Test
    fun `a filter a crawler sends to the market keys the answer`() {
        // Kleinanzeigen puts minimum mileage into its URL, so the answer fetched with it is not
        // the answer to the search without it.
        assertNotEquals(key(base), key(base.copy(carFilters = CarFilters(minMileageKm = 50_000))))
        assertNotEquals(key(base), key(base.copy(startPage = 2)))
        assertNotEquals(key(base), key(base.copy(reach = base.reach.copy(otherWords = true))))
    }

    @Test
    fun `what is judged after the cache shares the answer`() {
        assertEquals(key(base), key(base.copy(sort = SortMode.PRICE_ASC, excludeKeywords = listOf("defekt"))))
    }
}
