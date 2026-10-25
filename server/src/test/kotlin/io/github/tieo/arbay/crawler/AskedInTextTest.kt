package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock

class AskedInTextTest {
    private fun ad(euros: Long, text: String?) = Listing(
        id = "SUBITO:1", platformId = PlatformId.SUBITO, externalId = "1", url = "https://www.subito.it/x.htm",
        title = "LAGLER HUMMEL", description = text, price = Money(euros * 100, Currency.EUR), scrapedAt = Clock.System.now(),
    )

    @Test
    fun `a token price under the ask the text states is the ask`() {
        // Subito, live: listed at 24 €, a 700 € alert went out for it.
        val subito = ad(24, "Vendo \nLAGLER HUMMEL \nLevigatrice parquet \n2, 2KW \nPrezzo 2400€\nTelefono.")
        assertEquals(240000, listOf(subito).markAskedInText().single().price.amount)
    }

    @Test
    fun `a new price, a year or a close figure leaves the listed price`() {
        assertNull(AskedInText.price("Neupreis 5600€, kaum benutzt"))
        assertNull(AskedInText.price("NP 7.000 €"))
        assertNull(AskedInText.price("Prezzo nuovo 5000€"))
        assertNull(AskedInText.price("Preis 2019 gekauft, Rechnung vorhanden"))
        assertEquals(2400, AskedInText.price("Preis: 2.400 € VB"))
        // Within the factor the market's own field stands: a seller who lowered the price.
        assertEquals(180000, listOf(ad(1800, "Preis 2400 €")).markAskedInText().single().price.amount)
        // Two asks that differ say nothing about which one stands.
        assertNull(AskedInText.price("Preis 2400 €, mit Randschleifer Preis 3100 €"))
    }
}
