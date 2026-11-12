package io.github.tieo.arbay.judging

import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.ImportSettings
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Location
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.VehicleInfo
import io.github.tieo.arbay.model.importDuty
import io.github.tieo.arbay.model.landedPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

class VehicleFitTest {
    private fun car(title: String, euros: Long, country: String? = "DE") = Listing(
        id = "AUTOSCOUT24:${title.hashCode()}", platformId = PlatformId.AUTOSCOUT24, externalId = title.hashCode().toString(),
        url = "https://www.autoscout24.de/angebote/x", title = title, price = Money(euros * 100, Currency.EUR),
        scrapedAt = Clock.System.now(), vehicle = VehicleInfo(mileageKm = 10), location = Location(country = country),
    )

    @Test
    fun `a damaged vehicle fits only a search that asks for damaged ones`() {
        val wreck = car("VW Crafter 2025", 12_491).let { it.copy(vehicle = it.vehicle!!.copy(condition = io.github.tieo.arbay.model.VehicleCondition.DAMAGED)) }
        assertNull(FitScore.score(wreck, CarFilters(), 12_491.0, 4.0))
        assertNull(FitScore.score(wreck, CarFilters(conditions = setOf(io.github.tieo.arbay.model.VehicleCondition.USED)), 12_491.0, 4.0))
        assertNotNull(FitScore.score(wreck, CarFilters(conditions = setOf(io.github.tieo.arbay.model.VehicleCondition.DAMAGED)), 12_491.0, 4.0))
    }

    @Test
    fun `a price a business alone gets rules the car out`() {
        val wishes = CarFilters(maxPriceEur = 50_000)
        assertNull(FitScore.score(car("Hyundai IONIQ 6 AKTION GEWERBE 77 kWh", 39_000), wishes, 39_000.0, 4.0))
        assertNotNull(FitScore.score(car("Hyundai IONIQ 6 77 kWh UNIQ", 39_000), wishes, 39_000.0, 4.0))
        assertNull(FitScore.score(car("BMW iX1 eDrive20 Gewerbe-Leasing ab 355 € mtl.", 39_000), wishes, 39_000.0, 4.0))
        assertNull(FitScore.score(car("Tesla Model Y Vanaf €35.990 met €5.000 Tesla inruilpremie", 35_990), wishes, 35_990.0, 4.0))
        assertNull(FitScore.score(car("Ford Explorer, Preis gilt nur bei Finanzierung über die Ford Bank", 33_000), wishes, 33_000.0, 4.0))
        // Leasing offered beside a cash price leaves the price standing.
        assertNotNull(FitScore.score(car("Ford Explorer Extended Range LEASINGAKTION!", 33_000), wishes, 33_000.0, 4.0))
        assertNull(FitScore.score(car("Kia EV3 58.3 KWh Gewerbe AIR Winterp. WP SHZ Dez/Jan", 30_449), wishes, 30_449.0, 4.0))
        assertNotNull(FitScore.score(car("Kia EV3 58.3 KWh AIR, Gewerbegebiet Nord", 30_449), wishes, 30_449.0, 4.0))
        assertNull(FitScore.score(car("Ford Explorer Select, Preis inkl. E-Umstiegs Prämie!! und bei Leasing Ford Credit!", 36_500), wishes, 36_500.0, 4.0))
        assertNull(FitScore.score(car("Mercedes-Benz EQA 250+ KEINE DEUTSCHE ZULASSUNG MÖGLICH!", 25_999), wishes, 25_999.0, 4.0))
    }

    @Test
    fun `a car from outside the EU lands with customs duty and import VAT on both`() {
        val swiss = car("Tesla Model 3", 30_000, country = "CH")
        val settings = ImportSettings()
        assertEquals(300_000L, swiss.importDuty(settings)?.amount)
        // 30,000 + 3,000 duty, and 19 % VAT on 33,000.
        assertEquals(3_927_000L, swiss.landedPrice(settings).amount)
        assertEquals(3_000_000L, car("Tesla Model 3", 30_000).landedPrice(settings).amount)
    }
}
