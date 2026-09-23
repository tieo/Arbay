package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.VanDimensions
import io.github.tieo.arbay.model.VanSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VanDimensionsTest {

    // ── other vans: the common scale ──────────────────────────────────────────────────────────

    @Test
    fun `a code on another van is read as written and is sure`() {
        val d = VanDimensions.read("Fiat Ducato L3H2 Kastenwagen")
        assertEquals(VanSize.LONG, d.length)
        assertEquals(VanSize.HIGH_ROOF, d.height)
        assertTrue(d.lengthSure && d.heightSure)
    }

    @Test
    fun `a spaced code parses`() {
        val d = VanDimensions.read("Ford Transit L2 H2 Trend")
        assertEquals(2, d.length)
        assertEquals(2, d.height)
    }

    @Test
    fun `a roof word on another van is a hint, not a fact`() {
        val d = VanDimensions.read("Mercedes Sprinter Superhochdach langer Radstand")
        assertEquals(VanSize.SUPER_HIGH_ROOF, d.height)
        assertEquals(VanSize.LONG, d.length)
        assertFalse(d.heightSure)
        assertFalse(d.lengthSure)
    }

    @Test
    fun `a code outside the common scale says nothing on another van`() {
        val d = VanDimensions.read("Renault Master L5 H4 text")
        assertNull(d.length)
        assertNull(d.height)
    }

    @Test
    fun `no size stated reads as nothing`() {
        assertTrue(VanDimensions.read("VW Golf 1.5 TSI Highline").isEmpty)
    }

    // ── the Crafter, in VW's terms ────────────────────────────────────────────────────────────

    @Test
    fun `a Crafter's Superhochdach and Normaldach are sure`() {
        for ((text, roof) in listOf(
            "VW Crafter 35 Kasten Normaldach" to VanSize.NORMAL_ROOF,
            "VW Crafter Superhochdach 4Motion" to VanSize.SUPER_HIGH_ROOF,
        )) {
            val d = VanDimensions.read(text)
            assertEquals(roof, d.height, text)
            assertTrue(d.heightSure, text)
        }
    }

    @Test
    fun `a seller's Hochdach is high or higher`() {
        val d = VanDimensions.read("VW Crafter 35 Kastenwagen Hochdach Automatik")
        assertEquals(VanSize.HIGH_ROOF, d.height)
        assertEquals(VanSize.SUPER_HIGH_ROOF, d.heightMax)
        assertFalse(d.heightSure)
    }

    @Test
    fun `words and a code narrow each other`() {
        // Hochdach is high or higher; L3H2 is normal or high on its two scales: together, high.
        val d = VanDimensions.read("Volkswagen Crafter 2.0 TDI 35 Lang Hochdach DSG L3H2")
        assertEquals(VanSize.HIGH_ROOF, d.height)
        assertTrue(d.heightSure)
        // Lang is long or longer; L3 is medium or long: together, long.
        assertEquals(VanSize.LONG, d.length)
        assertTrue(d.lengthSure)
    }

    @Test
    fun `VW's own codes are read in VW's terms`() {
        // "Crafter 35 Kastenwagen L4H4 TDI 4MOTION Automatik 8 Gang", as VW names it.
        val d = VanDimensions.read("Crafter 35 Kastenwagen L4H4 TDI 4MOTION Automatik 8 Gang")
        assertEquals(VanSize.SUPER_HIGH_ROOF, d.height)
        assertEquals(VanSize.LONG, d.length)
        assertTrue(d.heightSure && d.lengthSure)

        val l5 = VanDimensions.read("VW Crafter L5H3 mit verlängertem Überhang")
        assertEquals(VanSize.EXTRA_LONG, l5.length)
        assertEquals(VanSize.HIGH_ROOF, l5.height)
    }

    @Test
    fun `a Crafter code stands for what it names on either scale`() {
        // L3H2 is a medium Normaldach to VW and a long Hochdach on the common scale.
        val d = VanDimensions.read("VW Crafter 35 2.0 TDI Trendline L3H2 Aut.")
        assertEquals(VanSize.MEDIUM, d.length)
        assertEquals(VanSize.LONG, d.lengthMax)
        assertEquals(VanSize.NORMAL_ROOF, d.height)
        assertEquals(VanSize.HIGH_ROOF, d.heightMax)
        assertFalse(d.heightSure || d.lengthSure)
    }

    @Test
    fun `a Crafter code only the common scale has is read on it`() {
        val d = VanDimensions.read("VW Crafter L2H1 Kasten")
        assertEquals(VanSize.MEDIUM, d.length)
        assertEquals(VanSize.NORMAL_ROOF, d.height)
        assertTrue(d.lengthSure && d.heightSure)
    }

    @Test
    fun `the long wheelbase is long or longer, the medium one is settled`() {
        val long = VanDimensions.read("VW Crafter Kasten", wheelbaseMm = 4490)
        assertEquals(VanSize.LONG, long.length)
        assertEquals(VanSize.EXTRA_LONG, long.lengthMax)
        assertFalse(long.lengthSure)
        val medium = VanDimensions.read("VW Crafter Kasten", wheelbaseMm = 3640)
        assertEquals(VanSize.MEDIUM, medium.length)
        assertTrue(medium.lengthSure)
    }

    @Test
    fun `a Crafter search reads a listing that does not name its model as a Crafter`() {
        val d = VanDimensions.read("Kastenwagen L4H4 TDI Automatik", modelHint = "crafter")
        assertEquals(VanSize.SUPER_HIGH_ROOF, d.height)
        // A listing naming another model is read as that model, whatever was searched.
        assertNull(VanDimensions.read("Mercedes Sprinter L4H4", modelHint = "crafter").height)
    }

    @Test
    fun `a settled wheelbase tells which scale the code was written on`() {
        // On the 3640 mm wheelbase L3 is VW's L3, so its H2 is VW's Normaldach, not a Hochdach.
        val d = VanDimensions.read("Volkswagen Crafter 35 L3H2 4M Aut", wheelbaseMm = 3640)
        assertEquals(VanSize.MEDIUM, d.length)
        assertEquals(VanSize.NORMAL_ROOF, d.height)
        assertTrue(d.lengthSure && d.heightSure)
    }

    @Test
    fun `mittellang is medium`() {
        assertEquals(VanSize.MEDIUM, VanDimensions.read("vw crafter weiss mittel lang").length)
    }
}
