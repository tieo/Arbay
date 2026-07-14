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
    fun blankTextYieldsNull() {
        assertNull(VehicleTextParser.parse(""))
        assertNull(VehicleTextParser.parse(null))
    }

    @Test
    fun mileageBoundsAreSane() {
        val v = VehicleTextParser.parse("Crafter 250.000 km 2015")!!
        assertTrue(v.mileageKm!! in 1..2_000_000)
    }
}
