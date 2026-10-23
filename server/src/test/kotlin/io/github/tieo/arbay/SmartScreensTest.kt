package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.SmartScreens
import io.github.tieo.arbay.model.DriveVerdict
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tesseract's text of SMART screenshots from real ads, read on 2026-10-23, with each drive judged
 * by eye from the photo first. Serial numbers are taken out of the fixtures.
 */
class SmartScreensTest {
    private fun text(name: String) = javaClass.getResource("/fixtures/smart/$name.txt")!!.readText()

    @Test
    fun `a helium level under its threshold is a failing drive`() {
        val r = assertNotNull(SmartScreens.parse(text("scrutiny_wd_8tb_helium"), 2))
        assertEquals(DriveVerdict.FAILING, r.verdict)
        assertTrue(r.findings.any { "Helium 19" in it }, r.findings.toString())
    }

    @Test
    fun `tens of thousands of reallocated sectors and the tool's warning are a failing drive`() {
        val r = assertNotNull(SmartScreens.parse(text("crystaldiskinfo_de_ironwolf_12tb_reallocated"), 4))
        assertEquals(DriveVerdict.FAILING, r.verdict)
        assertEquals(47_520L, r.reallocated)
        assertEquals(131L, r.uncorrectable)
        assertEquals("Vorsicht", r.status)
        assertEquals(3_579L, r.powerOnHours)
    }

    @Test
    fun `one pending sector is a failing drive, and its hours are read`() {
        val r = assertNotNull(SmartScreens.parse(text("crystaldiskinfo_de_wdred_6tb_pending"), 2))
        assertEquals(DriveVerdict.FAILING, r.verdict)
        assertEquals(1L, r.pending)
        assertEquals(95_285L, r.powerOnHours)
    }

    @Test
    fun `every sector count zero is a clean drive`() {
        val r = assertNotNull(SmartScreens.parse(text("crystaldiskinfo_de_toshiba_18tb_clean"), 3))
        assertEquals(DriveVerdict.CLEAN, r.verdict, r.toString())
        assertEquals(37_150L, r.powerOnHours)
        assertEquals(listOf(0L, 0L, 0L), listOf(r.reallocated, r.pending, r.uncorrectable))
    }

    @Test
    fun `a screenshot cut off before the pending sectors says too little to call clean`() {
        val r = assertNotNull(SmartScreens.parse(text("crystaldiskinfo_de_dell_4tb_clean"), 2))
        assertEquals(DriveVerdict.UNREADABLE, r.verdict, r.toString())
        assertEquals(0L, r.reallocated)
        assertEquals(26_129L, r.powerOnHours)
    }

    @Test
    fun `a sector count no drive can have is a misread and stays unread`() {
        // The server's own pass over the WD Red's screenshot read its reallocated row's hex as 0x900000000000.
        val r = assertNotNull(SmartScreens.parse(text("crystaldiskinfo_de_wdred_6tb_pending").replace(
            "05 Wiederzugewiesene Sektoren 200 200 140 000000000000", "05 Wiederzugewiesene Sektoren 200 200 140 900000000000"), 2))
        assertNull(r.reallocated)
        assertEquals(DriveVerdict.FAILING, r.verdict)
        assertEquals(1L, r.pending)
    }

    @Test
    fun `a photo without a SMART table is no reading`() {
        assertNull(SmartScreens.parse("TOSHIBA MG09ACA18TE 18TB\nMADE IN PHILIPPINES\n+12V DC 0.75A", 1))
    }
}
