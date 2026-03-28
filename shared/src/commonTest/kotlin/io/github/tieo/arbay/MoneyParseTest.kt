package io.github.tieo.arbay

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MoneyParseTest {

    private fun assertParse(text: String, expectedCents: Long, expectedCurrency: Currency) {
        val result = Money.parse(text)
        assertNotNull(result, "Failed to parse: '$text'")
        assertEquals(expectedCents, result.amount, "Wrong amount for '$text'")
        assertEquals(expectedCurrency, result.currency, "Wrong currency for '$text'")
    }

    // === US Dollar prices ===

    @Test
    fun `USD whole dollar`() = assertParse("$100", 10000, Currency.USD)

    @Test
    fun `USD with cents dot-decimal`() = assertParse("$100.00", 10000, Currency.USD)

    @Test
    fun `USD with non-zero cents`() = assertParse("$99.99", 9999, Currency.USD)

    @Test
    fun `USD large price with comma thousands`() = assertParse("$1,234.56", 123456, Currency.USD)

    @Test
    fun `USD large price no cents`() = assertParse("$1,000", 100000, Currency.USD)

    @Test
    fun `USD small price`() = assertParse("$5.50", 550, Currency.USD)

    @Test
    fun `USD text format`() = assertParse("USD 250.00", 25000, Currency.USD)

    @Test
    fun `USD no decimals large`() = assertParse("$10000", 1000000, Currency.USD)

    // === Euro prices (comma-decimal, dot-thousands) ===

    @Test
    fun `EUR simple`() = assertParse("€100", 10000, Currency.EUR)

    @Test
    fun `EUR with comma cents`() = assertParse("€99,99", 9999, Currency.EUR)

    @Test
    fun `EUR with dot thousands and comma cents`() = assertParse("€1.234,56", 123456, Currency.EUR)

    @Test
    fun `EUR dot thousands no cents`() = assertParse("€1.234", 123400, Currency.EUR)

    @Test
    fun `EUR text format`() = assertParse("EUR 250,00", 25000, Currency.EUR)

    @Test
    fun `EUR defaults to EUR when no symbol`() = assertParse("100,00", 10000, Currency.EUR)

    @Test
    fun `EUR large with dots`() = assertParse("€10.000", 1000000, Currency.EUR)

    // === GBP ===

    @Test
    fun `GBP with pence`() = assertParse("£49.99", 4999, Currency.GBP)

    @Test
    fun `GBP whole pounds`() = assertParse("£500", 50000, Currency.GBP)

    @Test
    fun `GBP with thousands comma`() = assertParse("£1,299.00", 129900, Currency.GBP)

    // === CHF ===

    @Test
    fun `CHF simple`() = assertParse("CHF 150", 15000, Currency.CHF)

    @Test
    fun `CHF with cents`() = assertParse("CHF 99.90", 9990, Currency.CHF)

    // === Edge cases ===

    @Test
    fun `price with spaces around`() = assertParse("  € 100,50  ", 10050, Currency.EUR)

    @Test
    fun `empty string returns null`() = assertNull(Money.parse(""))

    @Test
    fun `no digits returns null`() = assertNull(Money.parse("€"))

    @Test
    fun `mixed EUR format with both separators`() = assertParse("€2.499,00", 249900, Currency.EUR)

    @Test
    fun `mixed USD format with both separators`() = assertParse("$2,499.00", 249900, Currency.USD)

    // === The critical bug case: USD with dot decimal should NOT become 10x ===

    @Test
    fun `USD 100 dot 00 is 100 dollars not 10000`() {
        val result = Money.parse("$100.00")
        assertNotNull(result)
        assertEquals(10000, result.amount, "100.00 USD = 10000 cents, NOT 1000000")
        assertEquals(Currency.USD, result.currency)
    }

    @Test
    fun `EUR 10 dot 000 is 10000 euros`() {
        // European "10.000" = 10,000 (dot is thousands separator, 3 digits after)
        val result = Money.parse("€10.000")
        assertNotNull(result)
        assertEquals(1000000, result.amount, "10.000 EUR = 10000 euros = 1000000 cents")
    }
}
