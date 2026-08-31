package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.QueryResultCache
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class QueryResultCacheKeyTest {

    private fun query(cf: CarFilters?) = SearchQuery(text = "volkswagen crafter", carFilters = cf, category = MarketGroup.VEHICLES)

    @Test
    fun postFilterDimsShareCacheEntry() {
        // Van size / body / colour / description are enforced after the cache, so changing them
        // must NOT change the key — else every filter tweak re-crawls.
        val a = query(CarFilters(vanHeights = setOf(2)))
        val b = query(CarFilters(vanHeights = setOf(3), bodyTypes = setOf(io.github.tieo.arbay.model.BodyType.TRANSPORTER), descriptionContains = "camper"))
        assertEquals(
            QueryResultCache.key(PlatformId.KLEINANZEIGEN, a),
            QueryResultCache.key(PlatformId.KLEINANZEIGEN, b),
        )
    }

    @Test
    fun fuelSplitsCacheEntry() {
        // Fuel is baked into Kleinanzeigen's fetch URL, so it changes what is crawled.
        val diesel = query(CarFilters(fuels = setOf(Fuel.DIESEL)))
        val petrol = query(CarFilters(fuels = setOf(Fuel.PETROL)))
        assertNotEquals(
            QueryResultCache.key(PlatformId.KLEINANZEIGEN, diesel),
            QueryResultCache.key(PlatformId.KLEINANZEIGEN, petrol),
        )
    }

    @Test
    fun platformSplitsCacheEntry() {
        val q = query(CarFilters())
        assertNotEquals(
            QueryResultCache.key(PlatformId.KLEINANZEIGEN, q),
            QueryResultCache.key(PlatformId.MOBILE_DE, q),
        )
    }
}
