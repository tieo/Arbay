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
}
