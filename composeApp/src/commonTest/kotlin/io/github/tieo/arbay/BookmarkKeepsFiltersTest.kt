package io.github.tieo.arbay

import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.SortMode
import io.github.tieo.arbay.ui.screen.bookmarkQuery
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Saving a search keeps what the reader narrowed it to.
 *
 * Off a real phone: a screen showing 188 offers with four filters became 252 with two the moment
 * the bookmark was tapped, because the saved query was rebuilt from the search text and its markets
 * and the rest was dropped.
 */
class BookmarkKeepsFiltersTest {

    private val narrowed = SearchQuery(
        text = "iphone 11",
        category = MarketGroup.GENERAL,
        platforms = listOf(PlatformId.EBAY_DE, PlatformId.KLEINANZEIGEN),
        minPrice = Money(1_000, Currency.EUR),
        maxPrice = Money(62_900, Currency.EUR),
        condition = listOf(Condition.USED),
        conditionUnstated = false,
        saleTypes = listOf(SaleType.FIXED_PRICE),
        saleTypeUnstated = false,
        sort = SortMode.PRICE_ASC,
        showOnlyMarkets = setOf(PlatformId.EBAY_DE),
        showOnlyCountries = setOf("DE"),
    )

    @Test
    fun `every narrowing survives being bookmarked`() {
        val saved = bookmarkQuery(
            onScreen = narrowed,
            text = "iphone 11",
            asked = listOf(PlatformId.EBAY_DE, PlatformId.KLEINANZEIGEN),
            category = MarketGroup.GENERAL,
            carFilters = null,
            blockedWords = listOf("hülle"),
            aliases = emptyList(),
        )
        assertEquals(narrowed.minPrice, saved.minPrice, "the price band")
        assertEquals(narrowed.maxPrice, saved.maxPrice, "the price band")
        assertEquals(narrowed.condition, saved.condition, "the conditions")
        assertEquals(false, saved.conditionUnstated, "including the ones that state none")
        assertEquals(narrowed.saleTypes, saved.saleTypes, "how it is sold")
        assertEquals(false, saved.saleTypeUnstated)
        assertEquals(SortMode.PRICE_ASC, saved.sort, "the order")
        assertEquals(setOf(PlatformId.EBAY_DE), saved.showOnlyMarkets, "the markets shown")
        assertEquals(setOf("DE"), saved.showOnlyCountries)
        assertEquals(listOf("hülle"), saved.excludeKeywords, "the blocked words as they stand now")
    }

    @Test
    fun `a search with nothing narrowed is saved as it is`() {
        val saved = bookmarkQuery(
            onScreen = null,
            text = "iphone 11",
            asked = listOf(PlatformId.EBAY_DE),
            category = MarketGroup.GENERAL,
            carFilters = null,
            blockedWords = emptyList(),
            aliases = emptyList(),
        )
        assertEquals("iphone 11", saved.text)
        assertEquals(listOf(PlatformId.EBAY_DE), saved.platforms)
        assertEquals(null, saved.condition)
    }
}
