package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.MarktplaatsDetailParser
import io.github.tieo.arbay.model.Fuel
import io.github.tieo.arbay.model.SellerType
import io.github.tieo.arbay.model.Transmission
import io.github.tieo.arbay.model.VehicleField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarktplaatsDetailParserTest {

    // The detail page hydration state embeds the spec set as an attr object; this is the shape.
    private val detailHtml = """
        <html><body><script>window.__STATE__ = {"attr":{
        "Model omschrijving":"Crafter","Vermogen":"142","Bouwjaar":"2018","fuel":"Diesel",
        "Kilometerstand":"232583","Transmissie":"6 versnellingen, Handgeschakeld",
        "Carrosserievorm":"Bestelbus","Aantal deuren":"5","Aantal zitplaatsen":"3","color":"Grijs"
        }}</script></body></html>
    """.trimIndent()

    @Test
    fun `parses the full spec set from the detail attribute object`() {
        val v = MarktplaatsDetailParser.parse(detailHtml)!!
        assertEquals(2018, v.firstRegYear)
        assertEquals(232583, v.mileageKm)
        assertEquals(Fuel.DIESEL, v.fuel)
        assertEquals(Transmission.MANUAL, v.gearbox)
        assertEquals(5, v.doors)
        assertEquals(3, v.seats)
        assertEquals("Grijs", v.color)
        // 142 PK -> ~104 kW.
        assertEquals(104, v.powerKw)
    }

    @Test
    fun `detail specs are verified so a filter can exclude on them`() {
        val v = MarktplaatsDetailParser.parse(detailHtml)!!
        assertTrue(v.isVerified(VehicleField.POWER))
        assertTrue(v.isVerified(VehicleField.GEARBOX))
        assertTrue(v.isVerified(VehicleField.FUEL))
    }

    @Test
    fun `a page without the attribute object yields null`() {
        assertNull(MarktplaatsDetailParser.parse("<html><body>no specs</body></html>"))
    }

    @Test
    fun `reads the seller's whole text, the seller and every photo of an ad`() {
        val html = javaClass.getResource("/fixtures/marktplaats_ad.html")!!.readText()
        val d = MarktplaatsDetailParser.parseAd(html)!!
        val text = d.description!!
        assertTrue(text.startsWith("Te koop aangeboden: een uitstekend werkende WD Blue 2TB M.2 SATA SSD."), text)
        // Past the cut the search card makes, and one line per line of the page.
        assertTrue("\nUitsluitend ophalen: Wordt niet verzonden.\n" in text, text)
        assertEquals("Verkoper", d.seller?.name)
        assertEquals(SellerType.PRIVATE, d.seller?.type)
        assertEquals(4, d.photos.size)
        assertTrue(d.photos.all { it.startsWith("https://images.marktplaats.com/") }, d.photos.toString())
        assertNull(d.vehicle)
    }
}
