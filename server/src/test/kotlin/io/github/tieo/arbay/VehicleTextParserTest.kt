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
    fun electricEquipmentIsNotElectricFuel() {
        // mobile.de 456337184: a 103 kW TDI dealer van whose equipment list reads electric.
        val text = "Ausstattung: Elektr. Fensterheber, Servolenkung elektro-mechanisch, " +
            "Fensterheber elektrisch vorn, Motor 2,0 Ltr. - 103 kW TDI"
        assertEquals(Fuel.DIESEL, VehicleTextParser.parse(text)!!.fuel)
        assertNull(VehicleTextParser.parse("Crafter Kasten, Servolenkung elektro-mechanisch, Spiegel elektrisch")?.fuel)
    }

    @Test
    fun electricWordsBesideANamedEngineReadAsThatEngine() {
        assertEquals(Fuel.DIESEL, VehicleTextParser.parse("VW Crafter 2.0 TDI, Elektroinstallation 230V")!!.fuel)
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
    fun serviceDateIsNotFirstRegYear() {
        // A Remscheid dealer's Crafter (EZ 10/2019) lists its service history; the last entry's
        // year is no registration year.
        val v = VehicleTextParser.parse(
            "Volkswagen Crafter Kasten 35 mittellang Hochdach FWD Kamera Servicehistorie: " +
                "1. Service am 03.11.2021 bei 19.339 Km 4. Service am 20.01.2026 bei 66.031 Km",
        )
        assertNull(v?.firstRegYear)
    }

    @Test
    fun labeledFullDateGivesFirstRegYear() {
        val v = VehicleTextParser.parse("Volkswagen Crafter 2.0 TDI - 1. Hand - EZ. 10.07.2014 - Kilometerstand: 214253 - HU bis 08/28")!!
        assertEquals(2014, v.firstRegYear)
    }

    @Test
    fun validForYearIsInspectionNotFirstRegYear() {
        // A private eBay seller's Euro 5 Crafter: "gültig für 2025" is the TÜV, not the build year.
        val v = VehicleTextParser.parse("volkswagen crafter 2L Euro 5 Wolkswagen craftar, Motor 2 l, Heckantrieb, 6 Gänge, Euro 5, hat keine Roststellen, gültig für 2025.")
        assertNull(v?.firstRegYear)
    }

    @Test
    fun automaticEquipmentIsNotAnAutomaticGearbox() {
        // A Hildesheim dealer's manual Crafter: its equipment list is full of automatic things.
        val v = VehicleTextParser.parse(
            "Volkswagen Crafter Kasten 2.0 TDI L2H2 FWD - Fahrlichtschaltung automatisch (Coming-Home) " +
                "- Automatische Distanzregelung ACC - Start-Stopp-Automatik - Klimaautomatik",
        )
        assertNull(v?.gearbox)
    }

    @Test
    fun statedGearboxFieldWins() {
        val v = VehicleTextParser.parse("Crafter Start-Stop-Automatik, Erstzulassung: 10/2021 Getriebe: Schaltgetriebe Klimatisierung: Klimaanlage")!!
        assertEquals(Transmission.MANUAL, v.gearbox)
        val a = VehicleTextParser.parse("Crafter 35 TDI Kasten Getriebe: Automatik")!!
        assertEquals(Transmission.AUTOMATIC, a.gearbox)
    }

    @Test
    fun aYearBeforeTheMileageIsNotPartOfIt() {
        // A Wrestedt seller's one-line ad.
        val v = VehicleTextParser.parse("Vw Crafter 180 PS BJ 2018 185000Km Scheckheft Tüv Neu Sortimo Werkstattausrüstung")!!
        assertEquals(185_000, v.mileageKm)
        assertEquals(2018, v.firstRegYear)
        assertEquals(125_000, VehicleTextParser.parse("Crafter EZ 2019 125 000 km")!!.mileageKm)
        assertEquals(88_141, VehicleTextParser.parse("Crafter, 88.141 km, Automatik")!!.mileageKm)
    }

    @Test
    fun gearsAloneAreTheManual() {
        // A Cloppenburg dealer's Crafter: VW's equipment list names the manual by its gears only.
        val v = VehicleTextParser.parse("Crafter 35 Kasten, Radstand 3640 mm, Antriebsart: Frontantrieb, Getriebe 6-Gang, Über 150 Transporter")!!
        assertEquals(Transmission.MANUAL, v.gearbox)
        val a = VehicleTextParser.parse("Crafter 35 Kasten, Getriebe 8-Gang-Automatik, Start-Stopp-Automatik")!!
        assertEquals(Transmission.AUTOMATIC, a.gearbox)
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

    @Test
    fun `an ad that names no fuel says nothing about it`() {
        val v = VehicleTextParser.parse("VW Crafter L2H2 Automatik/Kamera/Klima/PDC, 2021, 119.292 km")
        assertEquals(null, v?.fuel)
    }

    @Test
    fun `electronics are not an electric drive`() {
        val v = VehicleTextParser.parse("Crafter Kasten lang plus HD, Elektronisches Stabilitätsprogramm, Elektronik geprüft, 2.0 TDI")
        assertEquals(io.github.tieo.arbay.model.Fuel.DIESEL, v?.fuel)
    }

    @Test
    fun `a service date written as month and year is not the first registration`() {
        val v = VehicleTextParser.parse(
            "Volkswagen Crafter 2.0 TDI mittellang verglast Klima, Navi VW Crafter 2.0 TDI mittellang 140PS\n" +
                "- Fahrzeug aus 1. Hand\n- Scheckheftgepflegt bei VW\n- Letzter Service in 07.2026 bei 140.547 Km\n- Guter Zustand",
        )
        assertNull(v?.firstRegYear)
        assertEquals(140_547, v?.mileageKm)
    }

    @Test
    fun `a service log of odometer readings is at least the mileage`() {
        val v = VehicleTextParser.parse(
            "VW Crafter L2 H2, Led Scheinwerfer, AHK Zum Verkauf steht ein schöner VW Crafter L2 H2\nim super Zustand\n" +
                "Alle Wartungsarbeiten wurden regelmäßig durchgeführt , hier die letzte Arbeiten :\n" +
                "237000 Zahnriemen mit Wasserpumpe + große Inspektion\n242000 Lichtmaschine (Bosch) erneut\n" +
                "244000 Klimakompressor erneut\n249000 Ölwechsel mit Durchsicht\nBremsen rundum neuwertig",
        )
        assertEquals(249_000, v?.mileageKm)
        assertNull(VehicleTextParser.parse("VW Crafter\n2018 Zahnriemen neu\nTÜV neu")?.mileageKm)
    }

    @Test
    fun `a year closing a sentence is not a mileage the next sentence names`() {
        val v = VehicleTextParser.parse(
            "Volkswagen Crafter Zum Verkauf steht ein VW Crafter, Baujahr September 2018.\n\nKilometerleistung 144.035.\n" +
                "Turbolader bei 140.000 gewechselt.",
        )
        assertEquals(2018, v?.firstRegYear)
        assertEquals(144_035, v?.mileageKm)
    }

    @Test
    fun `dated services and oil changes name no registration year`() {
        val history = VehicleTextParser.parse(
            "Volkswagen Crafter Kasten 35 mittellang Hochdach FWD Kamera Servicehistorie:\n1. Service am 03.11.2021 bei 19.339 Km\n" +
                "2. Service am 02.12.2022 bei 33.215 Km\n3. Service am 06.02.2025 bei 50.611 Km\n4. Service am 20.01.2026 bei 66.031 Km",
        )
        assertNull(history?.firstRegYear)
        val oil = VehicleTextParser.parse(
            "Volkswagen CRAFTER FLACHDACH AHK PIONEER-SOUNDSYSTEM 140-PS VOLKSWAGEN CRAFTER FLACHDACH\n" +
                "LETZTE ÖLWECHSEL BEI 107.800 KM AM 15.05.2024\nANHÄNGERKUPPLUNG MIT TRITTBRETT",
        )
        assertNull(oil?.firstRegYear)
    }
}

