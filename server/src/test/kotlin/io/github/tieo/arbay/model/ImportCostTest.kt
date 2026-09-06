package io.github.tieo.arbay.model

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportCostTest {

    private fun listing(platform: PlatformId, cents: Long, country: String? = null) = Listing(
        id = "$platform:1",
        platformId = platform,
        externalId = "1",
        url = "https://example.com/1",
        title = "Crucial 32GB DDR4-3200",
        price = Money(cents, Currency.USD),
        location = country?.let { Location(country = it) },
        scrapedAt = Instant.parse("2026-09-06T10:00:00Z"),
    )

    private val de = ImportSettings(homeCountry = "DE", importVatPercent = 19)

    @Test
    fun `a listing from outside the EU lands with import VAT on top`() {
        // The case this was built for: $150 on eBay COM is what eBay itself shows a German buyer
        // as $178.50 including VAT.
        val landed = listing(PlatformId.EBAY_COM, 150_00).landedPrice(de)
        assertEquals(178_50, landed.amount)
        assertEquals(Currency.USD, landed.currency)
    }

    @Test
    fun `a listing from inside the EU is left alone`() {
        assertEquals(150_00, listing(PlatformId.EBAY_IT, 150_00).landedPrice(de).amount)
        assertEquals(150_00, listing(PlatformId.EBAY_DE, 150_00).landedPrice(de).amount)
        assertNull(listing(PlatformId.EBAY_IT, 150_00).importVat(de))
    }

    @Test
    fun `Switzerland counts as an import even though it is next door`() {
        assertEquals(178_50, listing(PlatformId.RICARDO, 150_00).landedPrice(de).amount)
    }

    @Test
    fun `the listing's own country wins over the market's`() {
        // AutoScout24 sells cars in many countries from one site; the listing says which.
        val swiss = listing(PlatformId.AUTOSCOUT24, 150_00, country = "CH")
        assertEquals(178_50, swiss.landedPrice(de).amount)
        val german = listing(PlatformId.AUTOSCOUT24_CH, 150_00, country = "DE")
        assertEquals(150_00, german.landedPrice(de).amount)
    }

    @Test
    fun `turning it off quotes what the market quotes`() {
        val off = de.copy(enabled = false)
        assertEquals(150_00, listing(PlatformId.EBAY_COM, 150_00).landedPrice(off).amount)
    }

    @Test
    fun `a buyer outside the EU gets no guessed charge`() {
        val us = ImportSettings(homeCountry = "US", importVatPercent = 19)
        assertEquals(150_00, listing(PlatformId.EBAY_DE, 150_00).landedPrice(us).amount)
    }

    @Test
    fun `shipping is inside the price the VAT is charged on`() {
        val withShipping = listing(PlatformId.EBAY_COM, 100_00)
            .copy(shipping = Shipping(cost = Money(20_00, Currency.USD)))
        assertEquals(142_80, withShipping.landedPrice(de).amount)
    }
}
