package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.VehicleTextParser
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.Transmission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VehicleTextParserTest {

    @Test
    fun parsesFullGermanListing() {
        val v = VehicleTextParser.parse("VW Crafter 35 2.0 TDI, EZ 03/2022, 145.000 km, 130 kW, Automatik")!!
        assertEquals(2022, v.firstRegYear)
        assertEquals(145_000, v.mileageKm)
        assertEquals(130, v.powerKw)
        assertEquals(Fuel.DIESEL, v.fuel)
        assertEquals(Transmission.AUTOMATIC, v.gearbox)
    }

    @Test
    fun convertsPsToKw() {
        val v = VehicleTextParser.parse("Golf VII 1.5 TSI 150 PS Schaltgetriebe Benzin Baujahr 2019")!!
        assertEquals(2019, v.firstRegYear)
        assertEquals(Fuel.PETROL, v.fuel)
        assertEquals(Transmission.MANUAL, v.gearbox)
        // 150 PS * 0.7355 ≈ 110 kW
        assertEquals(110, v.powerKw)
    }

    @Test
    fun ignoresEngineSizeAsMileage() {
        // "2.0" must not be read as 2 km; the km figure is 89000.
        val v = VehicleTextParser.parse("Passat 2.0 TDI 89.000 km")!!
        assertEquals(89_000, v.mileageKm)
    }

    @Test
    fun partsListingYieldsNoVehicleSignal() {
        // A parts/accessory listing has no year, mileage or power — post-filter can drop it.
        val v = VehicleTextParser.parse("Vw Lt wie bsp. Mercedes Sprinter t4 t5 Crafter Iveco Vario")
        // May detect a fuzzy body/fuel token but must not fabricate year/mileage/power.
        if (v != null) {
            assertNull(v.mileageKm)
            assertNull(v.powerKw)
            assertNull(v.firstRegYear)
        }
    }

    @Test
    fun parsesElectricAndPluginFuel() {
        assertEquals(Fuel.ELECTRIC, VehicleTextParser.parse("Tesla Model 3 Elektro 2021")!!.fuel)
        assertEquals(Fuel.PLUGIN_HYBRID, VehicleTextParser.parse("Passat GTE Plug-in Hybrid 2020")!!.fuel)
    }

    @Test
    fun structuredValuesWinOverText() {
        val fromText = VehicleTextParser.parse("Crafter 100.000 km 2019")
        val structured = io.github.tieo.arbay.model.VehicleInfo(firstRegYear = 2022, mileageKm = 145_000)
        val merged = VehicleTextParser.merge(structured, fromText)!!
        assertEquals(2022, merged.firstRegYear)
        assertEquals(145_000, merged.mileageKm)
    }

    @Test
    fun doesNotReadOdometerDigitsAsPower() {
        // "503.661 km" must not yield 661 PS -> ~486 kW. km is never a power unit.
        val v = VehicleTextParser.parse("VW Crafter Bus 503.661 km")
        assertEquals(null, v?.powerKw)
        assertEquals(503_661, v?.mileageKm)
    }

    @Test
    fun equipmentTextIsNotElectricFuel() {
        // "elektrische Fensterheber" (electric windows) must not make a TDI electric.
        val v = VehicleTextParser.parse("VW Crafter 2.0 TDI elektrische Fensterheber, Klima")!!
        assertEquals(Fuel.DIESEL, v.fuel)
    }

    @Test
    fun realElectricStillDetected() {
        assertEquals(Fuel.ELECTRIC, VehicleTextParser.parse("VW e-Crafter Elektro 2021")!!.fuel)
    }

    @Test
    fun blankTextYieldsNull() {
        assertNull(VehicleTextParser.parse(""))
        assertNull(VehicleTextParser.parse(null))
    }

    @Test
    fun mileageBoundsAreSane() {
        val v = VehicleTextParser.parse("Crafter 250.000 km 2015")!!
        assertTrue(v.mileageKm!! in 1..2_000_000)
    }

    @Test
    fun apkYearIsNotFirstRegYear() {
        // "APK tot 2027" is the Dutch inspection deadline; the build year is 2016.
        val v = VehicleTextParser.parse("Volkswagen Crafter 2016, 210.000 km, APK tot 2027")!!
        assertEquals(2016, v.firstRegYear)
        assertEquals(210_000, v.mileageKm)
    }

    @Test
    fun tuvYearIsNotFirstRegYear() {
        val v = VehicleTextParser.parse("Opel Corsa 2012, TÜV bis 06/2026, 98.000 km")!!
        assertEquals(2012, v.firstRegYear)
    }

    @Test
    fun inspectionOnlyYearYieldsNoRegYear() {
        // The only year in the text is an inspection deadline; no registration year exists.
        val v = VehicleTextParser.parse("VW Transporter, APK tot 2027, rijdt goed")
        assertNull(v?.firstRegYear)
    }

    @Test
    fun labeledYearBeatsInspectionYear() {
        val v = VehicleTextParser.parse("Golf 7, EZ 06/2018, TÜV bis 2026, 120.000 km")!!
        assertEquals(2018, v.firstRegYear)
    }

    @Test
    fun priceInEurosIsNotYear() {
        // "2000 €" is the asking price; the bare year fallback must keep 1989.
        val v = VehicleTextParser.parse("Opel Kadett Oldtimer 1989, fester Preis 2000 €")!!
        assertEquals(1989, v.firstRegYear)
    }

    @Test
    fun leaseAllowanceIsNotMileage() {
        // "10.000 km/Jahr" is a lease's annual allowance; the ad carries no odometer reading.
        val v = VehicleTextParser.parse("Leasing: VW ID.3 ab 299 € mtl., 10.000 km/Jahr, Automatik")
        assertNull(v?.mileageKm)
    }

    @Test
    fun odometerWinsOverLeaseAllowance() {
        val v = VehicleTextParser.parse("Leasingübernahme, 34.000 km Stand, 10.000 km pro Jahr")!!
        assertEquals(34_000, v.mileageKm)
    }

    @Test
    fun engineSizeAloneIsNotMileage() {
        // "2.0 km-Stand" pairs the engine size with the km label; 20 km would be fabricated.
        val v = VehicleTextParser.parse("Sharan 2.0 km-Stand unbekannt")
        assertNull(v?.mileageKm)
    }

    @Test
    fun thousandsFigureIsNotPower() {
        // "1.200 PS" exceeds the plausible range; its tail must not be read as 200 PS.
        val v = VehicleTextParser.parse("Dragster Umbau 1.200 PS, Bj 2015")
        assertNull(v?.powerKw)
        assertEquals(2015, v?.firstRegYear)
    }
}
