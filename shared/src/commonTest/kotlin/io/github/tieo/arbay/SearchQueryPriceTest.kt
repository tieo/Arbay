package io.github.tieo.arbay

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.toCarFilters
import io.github.tieo.arbay.model.withCarFilters
import io.github.tieo.arbay.model.withPriceRangeEur
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The price band has one home — the query's minPrice/maxPrice — for every search, car or not. */
class SearchQueryPriceTest {

    @Test
    fun `storing car filters lifts the price out of them`() {
        val q = SearchQuery(text = "vw crafter", category = MarketGroup.VEHICLES)
            .withCarFilters(CarFilters(minPriceEur = 5_000, maxPriceEur = 20_000, minPowerKw = 110))

        assertEquals(Money(500_000, Currency.EUR), q.minPrice)
        assertEquals(Money(2_000_000, Currency.EUR), q.maxPrice)
        assertNull(q.carFilters?.minPriceEur, "the filter set must not keep a second copy")
        assertNull(q.carFilters?.maxPriceEur, "the filter set must not keep a second copy")
        assertEquals(110, q.carFilters?.minPowerKw)
    }

    @Test
    fun `reading car filters puts the price back`() {
        val q = SearchQuery(text = "vw crafter", category = MarketGroup.VEHICLES)
            .withCarFilters(CarFilters(minPriceEur = 5_000, maxPriceEur = 20_000, minPowerKw = 110))

        val f = q.toCarFilters()
        assertEquals(5_000, f?.minPriceEur)
        assertEquals(20_000, f?.maxPriceEur)
    }

    @Test
    fun `a price band alone is a filter, on a query with no car filters`() {
        val q = SearchQuery(text = "parkettschleifmaschine", category = MarketGroup.GENERAL).withPriceRangeEur(300, 700)

        assertEquals(Money(30_000, Currency.EUR), q.minPrice)
        assertEquals(Money(70_000, Currency.EUR), q.maxPrice)
        assertNull(q.carFilters)
        assertEquals(300, q.toCarFilters()?.minPriceEur)
    }

    @Test
    fun `narrowing the band leaves the other filters alone`() {
        val q = SearchQuery(text = "vw crafter", category = MarketGroup.VEHICLES)
            .withCarFilters(CarFilters(minPowerKw = 110, maxMileageKm = 200_000))
            .withPriceRangeEur(1_000, 15_000)

        assertEquals(110, q.toCarFilters()?.minPowerKw)
        assertEquals(200_000, q.toCarFilters()?.maxMileageKm)
        assertEquals(15_000, q.toCarFilters()?.maxPriceEur)
    }

    @Test
    fun `clearing the filters clears the price with them`() {
        val q = SearchQuery(text = "vw crafter", category = MarketGroup.VEHICLES)
            .withCarFilters(CarFilters(maxPriceEur = 20_000))
            .withCarFilters(null)

        assertNull(q.minPrice)
        assertNull(q.maxPrice)
        assertNull(q.toCarFilters())
    }
}
