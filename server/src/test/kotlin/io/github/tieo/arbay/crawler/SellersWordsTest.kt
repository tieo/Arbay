package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.VehicleField
import io.github.tieo.arbay.model.VehicleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Clock

class SellersWordsTest {
    // Kleinanzeigen, live 2026-10-16, both fields as the seller filled them in.
    private val koerne = """Angeboten wird ein sauberes Fahrzeug mit vielen Extras
Mwst ausweisbar
12.000€ Nettopreis (Brutto 14.280€)
Sauber & gepflegt
2. Vorbesitzer
Laufleistung: 297000"""

    private fun van(description: String, euros: Long = 12_000, km: Int? = 29_700) = Listing(
        id = "KLEINANZEIGEN:1", platformId = PlatformId.KLEINANZEIGEN, externalId = "1", url = "https://x",
        title = "VW Crafter MWST LED Schwingsitz ACC Kamera Navi 3er Kabine L2H2", description = description,
        price = Money(euros * 100, Currency.EUR), scrapedAt = Clock.System.now(),
        vehicle = VehicleInfo(mileageKm = km, verified = if (km != null) setOf(VehicleField.MILEAGE) else emptySet()),
    )

    @Test
    fun `the ad's own mileage and gross price win over the form`() {
        val read = SellersWords.apply(van(koerne))
        assertEquals(297_000, read.vehicle?.mileageKm)
        assertFalse(read.vehicle!!.isVerified(VehicleField.MILEAGE))
        assertEquals(14_280_00, read.price.amount)
    }

    @Test
    fun `an ad that agrees with its form, or names no net price, stays as it is`() {
        val plain = van("Kilometerstand 185.000 km\nMwSt. ausweisbar", euros = 14_280, km = 185_000)
        assertEquals(plain, SellersWords.apply(plain))
        // The listed price already the gross: nothing above it within VAT's reach.
        val gross = van("Netto 12.000 €, Brutto 14.280 €", euros = 14_280, km = null)
        assertEquals(14_280_00, SellersWords.apply(gross).price.amount)
        // A price-like number after the label is no mileage.
        val priced = van("Laufleistung: 12.000 €", km = 80_000)
        assertEquals(80_000, SellersWords.apply(priced).vehicle?.mileageKm)
    }
}
