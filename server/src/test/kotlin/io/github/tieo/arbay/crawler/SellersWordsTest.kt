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

    @Test
    fun `a price called net with no gross gets the buyer's VAT`() {
        // Kleinanzeigen Bassenheim, live 2026-10-24.
        val net = van("❗AHK 3.000 kg\n\n❗️Preis Netto zzgl MwSt.\n\nGerne nehmen wir ihre gebrauchten Nutzfahrzeuge in Zahlung.", euros = 27_900, km = 92_000)
        assertEquals(33_201_00, SellersWords.apply(net).price.amount)
        // "Netto" about something else, or VAT already in the price, leaves it.
        val export = van("NETTO EXPORT INS EU-AUSLAND ODER DRITTLAND MÖGLICH", euros = 22_250, km = 99_663)
        assertEquals(22_250_00, SellersWords.apply(export).price.amount)
        // mobile.de Schrobenhausen, live 2026-10-24: the export price is the net, the asking price the gross.
        val exported = van("Weitere Infos gibt es per Telefon: 0175/7463771Export Preis netto: 15.950,- EModell: VW Crafter 35 D MLH", euros = 18_950, km = 88_999)
        assertEquals(18_950_00, SellersWords.apply(exported).price.amount)
        // AutoScout24 Riedlingen, live 2026-10-24: a net figure beside a gross asking price.
        val ownNet = van("Zul. Gesamtgewicht 3,50 t\n\n Ihr Nettopreis 20.672 €\nFahrzeugnummer: 020223", euros = 23_900, km = 119_292)
        assertEquals(23_900_00, SellersWords.apply(ownNet).price.amount)
        val shown = van("Nettopreis, MwSt. ausweisbar", euros = 20_000, km = 99_000)
        assertEquals(20_000_00, SellersWords.apply(shown).price.amount)
    }

    @Test
    fun `a page with no fields but the seller's words is read from its words`() {
        // A Kleinanzeigen commercial vehicle, live 2026-10-16: the page's only field is "Art: Transporter".
        val text = "Volkswagen Crafter 2.0 TDI\n- 1. Hand\n- HSN/TSN: 0603/BEV\n- EZ. 10.07.2014\n" +
            "- Kilometerstand: 214253\n- Schaltgetriebe\n- L2H2\n- Zahnriemen erneuert bei 203.000 Km\n- HU bis 08/28"
        val card = van("", euros = 13_900, km = null).copy(vehicle = null)
        val read = with(DetailEnricher) { card.withDetail(io.github.tieo.arbay.model.ListingDetail(description = text)) }.vehicle!!
        assertEquals(2014, read.firstRegYear)
        assertEquals(214_253, read.mileageKm)
        assertEquals(io.github.tieo.arbay.model.Transmission.MANUAL, read.gearbox)
        assertEquals(io.github.tieo.arbay.model.Fuel.DIESEL, read.fuel)
    }

    @Test
    fun `the ad's own words outrank the guess from its card`() {
        // Nienburg Crafter, live 2026-10-16: the card's summary named its Start-Stop-Automatik.
        val card = van("", euros = 24_999, km = null).copy(
            vehicle = io.github.tieo.arbay.model.VehicleInfo(gearbox = io.github.tieo.arbay.model.Transmission.AUTOMATIC),
        )
        val text = "Technik\n- Start-Stop-Automatik\nErstzulassung: 10/2021\nKilometerstand: 78.750 km\n" +
            "Leistung: 103 kW/140 PS\nGetriebe: Schaltgetriebe"
        val read = with(DetailEnricher) { card.withDetail(io.github.tieo.arbay.model.ListingDetail(description = text)) }.vehicle!!
        assertEquals(io.github.tieo.arbay.model.Transmission.MANUAL, read.gearbox)
        assertEquals(78_750, read.mileageKm)
        assertEquals(103, read.powerKw)
    }

    @Test
    fun `a price that only fills the field gives way to the price the text asks`() {
        val camper = van("Monatliche Rate: 545,28 EUR. 56.999€ VB bei zeitnaher Übernahme 52.000€ möglich.", euros = 12_345)
        assertEquals(56_999_00, SellersWords.apply(camper).price.amount)
        // A real asking price stays, and so does a placeholder with no price in the text.
        assertEquals(12_300_00, SellersWords.apply(van("56.999€ VB", euros = 12_300)).price.amount)
        assertEquals(12_345_00, SellersWords.apply(van("Gepflegter Camper", euros = 12_345)).price.amount)
    }
}
