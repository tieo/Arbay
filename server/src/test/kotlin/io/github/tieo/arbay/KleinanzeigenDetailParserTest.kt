package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.KleinanzeigenDetailParser
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VehicleField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KleinanzeigenDetailParserTest {

    private val html = javaClass.getResource("/fixtures/kleinanzeigen_detail.html")!!.readText()

    @Test
    fun parsesFullStructuredDetailTable() {
        val v = KleinanzeigenDetailParser.parse(html)!!
        assertEquals(2012, v.firstRegYear)
        assertEquals(11, v.firstRegMonth)
        assertEquals(228_076, v.mileageKm)
        assertEquals(Fuel.DIESEL, v.fuel)
        assertEquals(Transmission.MANUAL, v.gearbox)
        // 143 PS ≈ 105 kW
        assertEquals(105, v.powerKw)
        assertEquals(2, v.doors)          // "2/3" -> lower bound
        assertEquals(4, v.emissionClassEuro)   // "Euro4"
        assertEquals(4, v.emissionSticker)     // "4 (Grün)"
        assertEquals("Weiß", v.color)
        assertEquals("Stoff", v.upholstery)
        assertEquals("2028-03", v.inspectionUntil)  // "März 2028"
    }

    @Test
    fun everyParsedFieldIsVerified() {
        val v = KleinanzeigenDetailParser.parse(html)!!
        // The whole point: detail specs are verified, so power/gearbox can enforce filters.
        assertTrue(v.isVerified(VehicleField.POWER))
        assertTrue(v.isVerified(VehicleField.GEARBOX))
        assertTrue(v.isVerified(VehicleField.FUEL))
        assertTrue(v.isVerified(VehicleField.MILEAGE))
        assertTrue(v.isVerified(VehicleField.FIRST_REG_YEAR))
    }
}
