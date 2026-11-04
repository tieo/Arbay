package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.Currency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebCurrencyTest {
    @Test
    fun `an amount in a currency Arbay does not know is no amount, never euros`() {
        assertNull(WebCrawler.Amount(59_900, "RUB").money())
        assertNull(WebCrawler.Amount(1_000, "RON").money())
        assertEquals(Currency.CHF, WebCrawler.Amount(1_290, "CHF").money()?.currency)
        assertEquals(724L, WebCrawler.Amount(724, "EUR").money()?.amount)
    }
}
