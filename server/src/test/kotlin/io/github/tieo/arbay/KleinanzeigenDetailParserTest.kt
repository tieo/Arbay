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

    @Test
    fun readsTheCheapestShippingBesideThePrice() {
        // As the page of a phone ad showed it, 2026-10-08.
        val page = org.jsoup.Jsoup.parse(
            """<div><h2 class="boxedarticle--price">400 € VB</h2><p class="boxedarticle--old-price">450 €</p>
               <span class="boxedarticle--details--shipping"> + Versand ab 6,19 €</span></div>""",
        )
        val shipping = KleinanzeigenDetailParser.shipping(page)
        kotlin.test.assertEquals(619L, shipping?.cost?.amount)
        kotlin.test.assertEquals(null, KleinanzeigenDetailParser.shipping(org.jsoup.Jsoup.parse("<div></div>")))
    }

    @Test
    fun `an ad's page says whether it is still up`() {
        val live = javaClass.getResource("/fixtures/kleinanzeigen_detail.html")!!.readText()
        assertEquals(io.github.tieo.arbay.model.AdState.LIVE, KleinanzeigenDetailParser.adState(live))
        // A deleted ad keeps its page, title, price and text; only this setting changes.
        assertEquals(
            io.github.tieo.arbay.model.AdState.DELETED,
            KleinanzeigenDetailParser.adState(live.replace("showDeletedVeil: false", "showDeletedVeil: true")),
        )
        assertEquals(
            io.github.tieo.arbay.model.AdState.PAUSED,
            KleinanzeigenDetailParser.adState(live.replace("showPausedVeil: false", "showPausedVeil: true")),
        )
        assertEquals(null, KleinanzeigenDetailParser.adState("<html><body>nothing here</body></html>"))
    }
}

