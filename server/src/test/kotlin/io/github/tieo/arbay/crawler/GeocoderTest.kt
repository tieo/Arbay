package io.github.tieo.arbay.crawler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A town is typed the way it is signed, which is not always the way GeoNames writes it. */
class GeocoderTest {
    @Test
    fun `a river named after the town is also found in brackets`() {
        assertEquals("mühldorf (inn)", Geocoder.bracketForm("mühldorf am inn"))
        assertEquals("weil (rhein)", Geocoder.bracketForm("weil am rhein"))
        assertEquals("neumarkt (oberpfalz)", Geocoder.bracketForm("neumarkt in der oberpfalz"))
    }

    @Test
    fun `a plain name has no other form`() {
        assertNull(Geocoder.bracketForm("ulm"))
        assertNull(Geocoder.bracketForm("frankfurt (oder)"))
    }
}
