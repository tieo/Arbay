package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.VanDimensions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VanDimensionsTest {

    @Test
    fun parsesCombinedCode() {
        val d = VanDimensions.excludable("VW Crafter L3H2 Kastenwagen 2.0 TDI")
        assertEquals(3, d.length)
        assertEquals(2, d.height)
    }

    @Test
    fun parsesSpacedCombinedCode() {
        val d = VanDimensions.excludable("Ford Transit L2 H2 Trend")
        assertEquals(2, d.length)
        assertEquals(2, d.height)
    }

    @Test
    fun parsesStandaloneCodes() {
        val d = VanDimensions.excludable("Fiat Ducato Maxi L4, Hochdach")
        assertEquals(4, d.length)
        // No explicit H-code — excludable() must not read the roof word.
        assertNull(d.height)
    }

    @Test
    fun roofWordIsInferredButNotExcludable() {
        val text = "Mercedes Sprinter Superhochdach langer Radstand"
        assertNull(VanDimensions.excludable(text).height)
        val inf = VanDimensions.inferred(text)
        assertEquals(3, inf.height)  // Superhochdach → H3
        assertEquals(3, inf.length)  // langer Radstand → L3
    }

    @Test
    fun explicitCodeWinsOverWord() {
        // A stated H1 must not be overridden by the word "Hochdach".
        val inf = VanDimensions.inferred("Crafter H1 kein Hochdach umbau")
        assertEquals(1, inf.height)
    }

    @Test
    fun noSignalYieldsNull() {
        val d = VanDimensions.excludable("VW Golf 1.5 TSI Highline")
        assertNull(d.length)
        assertNull(d.height)
    }

    @Test
    fun outOfRangeCodesIgnored() {
        // "L5"/"H4" aren't in the L1-L4 / H1-H3 space we model.
        val d = VanDimensions.excludable("Random L5 H4 text")
        assertNull(d.length)
        assertNull(d.height)
    }
}
