package io.github.tieo.arbay.crawler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CountryNamesTest {
    @Test
    fun `a country is read in the language of any market`() {
        assertEquals("GB", CountryNames.iso("Regno Unito"))
        assertEquals("GB", CountryNames.iso("Vereinigtes Königreich"))
        assertEquals("CN", CountryNames.iso("Cina"))
        assertEquals("GB", CountryNames.fromOriginLine("aus Großbritannien"))
        assertEquals("DE", CountryNames.iso("Deutschland"))
        assertEquals("FR", CountryNames.iso("FR"))
    }

    @Test
    fun `an origin line gives its country and any other line gives none`() {
        assertEquals("DE", CountryNames.fromOriginLine("da Germania"))
        assertEquals("US", CountryNames.fromOriginLine("da Stati Uniti"))
        assertNull(CountryNames.fromOriginLine("Venditore privato"))
        assertNull(CountryNames.fromOriginLine("+EUR 6,19 per la consegna"))
        assertNull(CountryNames.fromOriginLine("Consegna gratuita"))
    }

    @Test
    fun `every country eBay has named a seller by is read`() {
        // Every origin eBay.de and eBay.it wrote on a card in the listing archive.
        val names = listOf("Großbritannien", "Vereinigte Staaten von Amerika", "Polen", "Italien", "Frankreich", "China", "Japan", "Litauen", "Spanien", "Kanada", "Österreich", "Niederlande", "Irland", "Tschechische Republik", "Israel", "Australien", "Südkorea", "Schweiz", "Hongkong", "Slowenien", "Portugal", "Griechenland", "Ungarn", "Rumänien", "Lettland", "Slowakei", "Schweden", "Malaysia", "Belgien", "Luxemburg", "Indien", "Malta", "Estland", "Dänemark", "Zypern", "Pakistan", "Mexiko", "Kroatien", "Bulgarien", "Germania", "Regno Unito", "Francia", "Australia", "Austria", "Irlanda", "Stati Uniti")
        assertEquals(emptyList(), names.filter { CountryNames.iso(it) == null })
    }
}
