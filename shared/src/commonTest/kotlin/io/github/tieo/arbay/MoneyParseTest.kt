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

    // === Free item price text handling (Kleinanzeigen "zu verschenken") ===

    @Test
    fun `Zu verschenken price text returns null from Money parse`() {
        // The crawler handles this specially (isFreeItem check) before calling Money.parse
        assertNull(Money.parse("Zu verschenken"), "Money.parse can't parse 'Zu verschenken' — crawler handles this")
    }

    @Test
    fun `Zu verschenken with leading text returns null`() {
        assertNull(Money.parse("Zu verschenken (Selbstabholung)"))
    }

    @Test
    fun `zero euro price parses correctly`() {
        val result = Money.parse("0 €")
        assertNotNull(result)
        assertEquals(0L, result.amount)
        assertEquals(Currency.EUR, result.currency)
    }

    @Test
    fun `zero with comma parses correctly`() {
        val result = Money.parse("0,00 €")
        assertNotNull(result)
        assertEquals(0L, result.amount)
    }

    @Test
    fun `VB suffix does not break price parse`() {
        // "VB" = Verhandlungsbasis (negotiable) — common Kleinanzeigen suffix
        val result = Money.parse("150 € VB")
        assertNotNull(result)
        assertEquals(15000L, result.amount)
        assertEquals(Currency.EUR, result.currency)
    }

    // === Nordic kroner with trailing period ("kr.") ===

    @Test
    fun `DKK price with trailing period from kr suffix`() {
        // "469.995 kr." strips to "469.995." — the dangling dot must not break the parse
        val result = Money.parse("469.995 kr.", Currency.DKK)
        assertNotNull(result)
        assertEquals(46999500L, result.amount)
        assertEquals(Currency.DKK, result.currency)
    }

    @Test
    fun `SEK price with kr suffix forced currency`() =
        assertEquals(4900000L, Money.parse("49 000 kr", Currency.SEK)?.amount)

    @Test
    fun `PLN price detected from zloty symbol`() =
        assertParse("45 000 zł", 4500000, Currency.PLN)

    @Test
    fun `CZK price detected from koruna symbol`() =
        assertParse("599 000 Kč", 59900000, Currency.CZK)

    @Test
    fun `prose with several numbers is not one number`() {
        // An eBay card whose own title carries the word "Versand" was read as a delivery charge,
        // and every digit in it ran together into 116422212.
        val title = "Kein Versand !!!!Apple iPhone 11 Schwarz 64GB A2221 MWLT2ZD/A Originalverpackung"
        assertEquals(1100L, Money.parse(title)?.amount, "only the first number in the text")
    }

    @Test
    fun `thousands written with spaces still parse`() {
        assertEquals(4900000L, Money.parse("49 000 kr", Currency.SEK)?.amount)
    }

    @Test
    fun `cents are rounded not truncated`() {
        // 19.99 * 100 is 1998.9999999999998 in binary floating point.
        assertEquals(1999L, Money.parse("19,99 €")?.amount)
        assertEquals(115L, Money.parse("1,15 €")?.amount)
    }

    @Test
    fun `a delivery charge with no currency is in the market's own`() {
        assertEquals(Money(450, Currency.GBP), Money.parseAtMarket("+ 4,50 Versand", Currency.GBP))
        assertEquals(Money(450, Currency.EUR), Money.parseAtMarket("+ 4,50 € Versand", Currency.GBP))
    }
}
