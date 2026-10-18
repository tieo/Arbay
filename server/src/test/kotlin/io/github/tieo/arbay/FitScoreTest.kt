package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CarFilterEngine
import io.github.tieo.arbay.crawler.FitScore
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Drivetrain
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.Seller
import io.github.tieo.arbay.model.SellerType
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VanSize
import io.github.tieo.arbay.model.VehicleInfo
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitScoreTest {

    // The wishes of the real search these weights were set on: a Crafter, mittellang Hochdach,
    // automatic, front-wheel drive, diesel, from 2018, up to 150,000 km, at least 110 kW.
    private val wishes = CarFilters(
        firstRegFromYear = 2018, maxMileageKm = 150_000, minPowerKw = 110,
        minPriceEur = 10_000, maxPriceEur = 30_990, transmission = Transmission.AUTOMATIC,
        drivetrain = Drivetrain.FWD, fuels = setOf(Fuel.DIESEL),
        vanLengths = setOf(VanSize.MEDIUM), vanHeights = setOf(VanSize.HIGH_ROOF),
    )

    private fun van(
        title: String = "Volkswagen Crafter Kasten 35 mittellang Hochdach FWD",
        description: String = "Klimaanlage",
        euros: Long = 23_950,
        vehicle: VehicleInfo = VehicleInfo(
            firstRegYear = 2022, mileageKm = 126_000, powerKw = 130, fuel = Fuel.DIESEL,
            gearbox = Transmission.AUTOMATIC, drivetrain = Drivetrain.FWD,
            vanLength = VanSize.MEDIUM, vanLengthMax = VanSize.MEDIUM,
            vanHeight = VanSize.HIGH_ROOF, vanHeightMax = VanSize.HIGH_ROOF,
        ),
        seller: Seller? = Seller(name = "Autohaus", type = SellerType.BUSINESS, rating = 4.9, reviewCount = 198),
        distanceKm: Double? = 300.0,
    ) = Listing(
        id = "MOBILE_DE:$title$euros", platformId = PlatformId.MOBILE_DE, externalId = "1", url = "https://x",
        title = title, description = description, price = Money(euros * 100, Currency.EUR),
        scrapedAt = Clock.System.now(), vehicle = vehicle, seller = seller, distanceKm = distanceKm,
    )

    private fun fit(l: Listing) = FitScore.score(l, wishes, FitScore.priceEur(l), softDriveHours = 4.0)

    @Test
    fun `a van that meets every wish misses nothing`() {
        val f = assertNotNull(fit(van()))
        assertEquals(emptyList(), f.misses)
        assertEquals(true, f.airConditioning)
    }

    @Test
    fun `a dealer under four stars rules the offer out`() {
        assertNull(fit(van(seller = Seller(name = "X", type = SellerType.BUSINESS, rating = 3.4, reviewCount = 26))))
    }

    @Test
    fun `a conversion or another fuel is not the van wanted`() {
        assertNull(fit(van(title = "Volkswagen Crafter California Camper")))
        assertNull(fit(van(vehicle = van().vehicle!!.copy(fuel = Fuel.ELECTRIC))))
    }

    @Test
    fun `a box with a tail lift, a car transporter and a sewer jetter are not closed vans`() {
        assertNull(fit(van(title = "Volkswagen Crafter", description = "Transportfahrzeug mit Ladebordwand zu verkaufen\nLaderaum ca. 22 m³")))
        assertNull(fit(van(title = "Volkswagen Crafter Algema Top Zustand Blitzlader")))
        assertNull(fit(van(title = "Spülwagen VW Crafter")))
    }

    @Test
    fun `a dealer's text sent as one line is no headline`() {
        // TruckScout24 sends a Cloppenburg dealer's equipment and terms as a single line; the retrofits
        // it offers ("Nachrüstung einer LBW (Ladebordwand)") are not the van's body.
        val text = "AHK bis 3.5 to (gegen Aufpreis)Karosserievariante: Hochdach in Wagenfarbe, Klimaanlage Climatic, " +
            "Radstand 3640 mm, Antriebsart: Frontantrieb, Über 150 Transporter und Busse auf Lager. Nachrüstung von: " +
            "Anhängerkupplungen, Standheizungen, Werkstatteinrichtungen, Nachrüstung einer LBW (Ladebordwand) gegen Aufpreis möglich."
        assertNotNull(fit(van(title = "Kastenwagen hoch VWCrafter 35 Kasten L3H3 AHK KLIMA HOLZBODEN", description = text)))
    }

    @Test
    fun `every miss costs something and is named`() {
        val perfect = fit(van())!!.score
        val noAc = fit(van(description = "Keine Klimaanlage oder -automatik"))!!
        val awd = fit(van(vehicle = van().vehicle!!.copy(drivetrain = Drivetrain.AWD)))!!
        assertTrue(noAc.score < perfect && "no air conditioning" in noAc.misses)
        assertTrue(awd.score < perfect && "AWD" in awd.misses)
    }

    @Test
    fun `cheaper is better all the way down, and distance counts little`() {
        assertTrue(fit(van(euros = 18_950))!!.score > fit(van(euros = 24_999))!!.score)
        val near = fit(van(distanceKm = 50.0))!!.score
        val far = fit(van(distanceKm = 550.0))!!.score
        assertTrue(near > far && near - far < 0.05, "near $near far $far")
    }

    @Test
    fun `a drive is estimated from the straight line`() {
        // Stuttgart to Hamburg: 534 km apart, under seven hours on the road.
        assertEquals(6.7, FitScore.driveHours(534.0)!!, 0.1)
    }
    @Test
    fun `a van beaten on price, power, age and mileage by one still online is beaten`() {
        // Live, 2026-10-16: a 140 PS Crafter from 06/2021 at 25,990 € against a 177 PS one from
        // 04/2021 at 19,971 € with 150,000 km: cheaper and stronger, but older and further driven.
        fun crafter(euros: Long, year: Int, month: Int, km: Int, kw: Int, title: String) = van(
            title = title, euros = euros,
            vehicle = VehicleInfo(firstRegYear = year, firstRegMonth = month, mileageKm = km, powerKw = kw),
        )
        val salzgitter = crafter(25_990, 2021, 6, 88_141, 103, "Salzgitter")
        val moenchengladbach = crafter(19_971, 2021, 4, 150_000, 130, "Mönchengladbach")
        assertNull(FitScore.beatenBy(salzgitter, listOf(salzgitter, moenchengladbach)))
        val better = crafter(24_500, 2021, 9, 80_000, 130, "better")
        assertEquals(better, FitScore.beatenBy(salzgitter, listOf(salzgitter, moenchengladbach, better)))
        // The same van on a second market, same numbers, beats nothing.
        val twin = crafter(25_990, 2021, 6, 88_141, 103, "twin")
        assertNull(FitScore.beatenBy(salzgitter, listOf(twin)))
        // A number unknown on the other side never beats.
        val unknownKm = van(title = "unknown", euros = 20_000, vehicle = VehicleInfo(firstRegYear = 2023, powerKw = 130))
        assertNull(FitScore.beatenBy(salzgitter, listOf(unknownKm)))
    }
    @Test
    fun `an ideal power fits fully and less power ranks lower without being ruled out`() {
        // The user on the Crafter: "177 very fitting score (not filter though)".
        val ideal = wishes.copy(minPowerKw = null, idealPowerKw = 130)
        fun withPower(kw: Int) = van(vehicle = van().vehicle!!.copy(powerKw = kw))
        fun fitOf(kw: Int) = assertNotNull(FitScore.score(withPower(kw), ideal, 23_950.0, softDriveHours = 4.0))
        assertEquals(emptyList(), fitOf(130).misses)
        val weaker = fitOf(103)
        assertTrue(weaker.score < fitOf(130).score)
        assertEquals(listOf("103 kW of 130"), weaker.misses)
        assertTrue(CarFilterEngine.apply(listOf(withPower(80)), ideal).isNotEmpty())
    }
}
