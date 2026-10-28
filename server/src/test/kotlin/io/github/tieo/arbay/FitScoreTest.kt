package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CarFilterEngine
import io.github.tieo.arbay.crawler.FitScore
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Drivetrain
import io.github.tieo.arbay.model.FitPart
import io.github.tieo.arbay.model.FitWish
import io.github.tieo.arbay.model.biggestLosses
import io.github.tieo.arbay.model.wholePoints
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
        val met = setOf(FitWish.SIZE, FitWish.GEARBOX, FitWish.POWER, FitWish.YEAR, FitWish.DRIVE, FitWish.SELLER, FitWish.AIR_CONDITIONING)
        assertEquals(emptyList(), f.parts.filter { it.wish in met && it.points < it.of - 1e-9 })
        assertEquals(true, f.airConditioning)
    }

    @Test
    fun `the parts add up to the fit, and in whole points to the fit as shown`() {
        val f = assertNotNull(fit(van(euros = 27_400, vehicle = van().vehicle!!.copy(powerKw = 103, gearbox = null))))
        assertEquals(f.score * 100, f.parts.sumOf { it.points }, 1e-9)
        assertEquals(100.0, f.parts.sumOf { it.of }, 1e-9)
        val whole = f.parts.wholePoints()
        assertEquals(Math.round(f.score * 100).toInt(), whole.sumOf { it.first })
        assertEquals(100, whole.sumOf { it.second })
        assertEquals(FitPart.NOT_STATED, f.parts.single { it.wish == FitWish.GEARBOX }.note)
        assertEquals(listOf(FitWish.PRICE, FitWish.POWER, FitWish.GEARBOX, FitWish.MILEAGE), f.parts.biggestLosses(4).map { it.first.wish })
    }

    @Test
    fun `a wish the search does not make counts for nothing`() {
        val few = CarFilters(maxPriceEur = 30_990)
        val f = assertNotNull(FitScore.score(van(), few, 23_950.0, softDriveHours = 4.0))
        assertEquals(null, f.parts.firstOrNull { it.wish == FitWish.GEARBOX || it.wish == FitWish.SIZE })
        assertEquals(100.0, f.parts.sumOf { it.of }, 1e-9)
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
    fun `an offer to traders or for export only is ruled out`() {
        assertNull(fit(van(description = "Verkauf ausschließlich für Export oder an Gewerbetreibende")))
        assertNull(fit(van(description = "Nur an Gewerbe!")))
        assertNull(fit(van(description = "nur Export")))
        assertNotNull(fit(van(description = "Wir verkaufen bevorzugt an Gewerbetreibende oder für den Export.")))
        assertNotNull(fit(van(description = "Das Fahrzeug wurde nur gewerblich genutzt, scheckheftgepflegt.")))
        assertNull(fit(van(description = "Verkauf nur an gewerbliche Kunden")))
    }

    @Test
    fun `a box with a tail lift, a car transporter and a sewer jetter are not closed vans`() {
        assertNull(fit(van(title = "Volkswagen Crafter", description = "Transportfahrzeug mit Ladebordwand zu verkaufen\nLaderaum ca. 22 m³")))
        assertNull(fit(van(title = "Volkswagen Crafter Algema Top Zustand Blitzlader")))
        assertNull(fit(van(title = "Spülwagen VW Crafter")))
    }

    @Test
    fun `an ambulance is no closed van, named in the title or only in the dealer's list of specs`() {
        // Cölbe: a 2022 Crafter with a Strobel RTW box, filed in Kleinanzeigen Nutzfahrzeuge.
        assertNull(fit(van(title = "Volkswagen Crafter Autom . Rettungswagen / DSG / LED")))
        assertNull(fit(van(title = "Volkswagen Crafter Autom . DSG / LED", description = "Bei Fragen bitte anrufen\n\nTransporter oder Lkw bis 7,5 t, Rettungswagen\nGebrauchtfahrzeug")))
        assertNotNull(fit(van(description = "Sonderausstattung: ...\n\nTransporter oder Lkw bis 7,5 t, Kastenwagen hoch + lang\nGebrauchtfahrzeug")))
    }

    @Test
    fun `a workshop fit-out is a closed van, a camper conversion is not`() {
        // Kolkwitz: a Würth demonstrator with Orsy shelving, sold as a Kastenwagen.
        assertNotNull(fit(van(title = "VW Crafter / 177 PS / 132 TKM / Würth Ausbau / HU 05/2028")))
        assertNotNull(fit(van(title = "VW Crafter 2.0 TDI Sortimo-Ausbau AHK")))
        assertNotNull(fit(van(title = "VW Crafter Kasten Werkstattausbau Klima")))
        assertNull(fit(van(title = "VW Crafter mit Ausbau, Bett und Küche")))
    }

    @Test
    fun `a dealer's text sent as one line is no headline`() {
        // TruckScout24 sends a Cloppenburg dealer's equipment and terms as a single line; the retrofits
        // it offers ("Nachrüstung einer LBW (Ladebordwand)") are not the van's body.
        val text = "AHK bis 3.5 to (gegen Aufpreis)Karosserievariante: Hochdach in Wagenfarbe, Klimaanlage Climatic, " +
            "Radstand 3640 mm, Antriebsart: Frontantrieb, Über 150 Transporter und Busse auf Lager. Nachrüstung von: " +
            "Anhängerkupplungen, Standheizungen, Werkstatteinrichtungen, Nachrüstung einer LBW (Ladebordwand) gegen Aufpreis möglich."
        assertNotNull(fit(van(title = "Kastenwagen hoch VWCrafter 35 Kasten L3H3 AHK KLIMA HOLZBODEN", description = text)))
        // A private seller in Kettig names the body in the opening words of a text written as one line.
        assertNull(fit(van(title = "vw crafter", description = "vw crafter 3,5 t Pritsche mit grade mal 139733 km runter und frischen TÜV " +
            "und im top Zustand rein setzen und los fahren ich bin der zweite Besitzer. Ich verkaufe den lkw weil ich ihn nicht mehr benötige")))
    }

    @Test
    fun `every miss costs something and is named`() {
        val perfect = fit(van())!!.score
        val noAc = fit(van(description = "Keine Klimaanlage oder -automatik"))!!
        assertTrue(noAc.score < perfect && noAc.parts.single { it.wish == FitWish.AIR_CONDITIONING }.note == "no")
    }

    @Test
    fun `another drive than the one wanted rules the van out, an unstated one costs a little`() {
        assertNull(fit(van(vehicle = van().vehicle!!.copy(drivetrain = Drivetrain.AWD))))
        assertNull(fit(van(vehicle = van().vehicle!!.copy(drivetrain = null), description = "Crafter 35 Kasten Heckantrieb")))
        assertNull(fit(van(vehicle = van().vehicle!!.copy(drivetrain = null), description = "Crafter 35 4MOTION")))
        val unstated = fit(van(vehicle = van().vehicle!!.copy(drivetrain = null)))!!
        assertTrue(unstated.score < fit(van())!!.score)
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
        fun powerOf(f: FitScore.Fit) = f.parts.single { it.wish == FitWish.POWER }
        assertEquals(powerOf(fitOf(130)).of, powerOf(fitOf(130)).points, 1e-9)
        val weaker = fitOf(103)
        assertTrue(weaker.score < fitOf(130).score)
        assertEquals("103 kW of 130", powerOf(weaker).note)
        // The user, on a 140 PS Crafter that fit 89 % against an ideal of 177 PS: "how is it 89%
        // with to low power". It costs about a fifth of the whole now, still short of ruling it out.
        assertTrue(fitOf(130).score - weaker.score > 0.12)
        assertTrue(powerOf(weaker).points > 0.0)
        assertTrue(CarFilterEngine.apply(listOf(withPower(80)), ideal).isNotEmpty())
    }
}
