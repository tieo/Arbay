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

    /** A fixture of two readings of one photo, at two sizes, as the server reads a SMART screen. */
    private fun readings(name: String) = text(name).split("\n\u000C\n")

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

    @Test
    fun `a WebP photo is read as an image, as willhaben serves every photo`() {
        val bytes = javaClass.getResource("/fixtures/smart/webp_probe.webp")!!.readBytes()
        val image = assertNotNull(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)))
        assertEquals(320, image.width)
    }

    @Test
    fun `each market's photo is asked for at its largest`() {
        assertEquals(
            "https://img.kleinanzeigen.de/api/v1/prod-ads/images/6c/6c9b?rule=\$_57.JPG",
            SmartScreens.fullSize("https://img.kleinanzeigen.de/api/v1/prod-ads/images/6c/6c9b?rule=\$_59.AUTO"),
        )
        assertEquals(
            "https://img.ricardostatic.ch/images/30dc/t_1800x1350/1tb-hdd",
            SmartScreens.fullSize("https://img.ricardostatic.ch/images/30dc/t_265x200/1tb-hdd"),
        )
        assertEquals(
            "https://cache.willhaben.at/mmo/1/137/351/7931_-1526906973.jpg",
            SmartScreens.fullSize("https://cache.willhaben.at/mmo/1/137/351/7931_-1526906973_hoved.jpg"),
        )
        assertEquals("https://i.ebayimg.com/images/g/abc/s-l1600.jpg", SmartScreens.fullSize("https://i.ebayimg.com/images/g/abc/s-l500.webp"))
    }

    @Test
    fun `raw values shown in decimal are read as decimal`() {
        val r = assertNotNull(SmartScreens.parse(text("crystaldiskinfo_de_decimal_clean"), 5))
        assertEquals(DriveVerdict.CLEAN, r.verdict)
        assertEquals(401L, r.powerOnHours)
    }

    @Test
    fun `an older CrystalDiskInfo's names for the same rows are read`() {
        val r = assertNotNull(SmartScreens.parse(text("crystaldiskinfo_de_old_names_clean"), 5))
        assertEquals(DriveVerdict.CLEAN, r.verdict)
        assertEquals(0L, r.pending)
        assertEquals(27_780L, r.powerOnHours)
    }

    @Test
    fun `a photo of a monitor with the window's edge read after each row still reads`() {
        val r = assertNotNull(SmartScreens.parse(text("crystaldiskinfo_de_monitor_photo_clean"), 2))
        assertEquals(DriveVerdict.CLEAN, r.verdict)
        assertEquals(6_884L, r.powerOnHours)
        assertEquals("Gut", r.status)
    }

    @Test
    fun `a row whose current value reads under its worst is a misread, not a failing drive`() {
        val r = assertNotNull(SmartScreens.parse(text("crystaldiskinfo_de_monitor_photo_misread"), 3))
        assertTrue(r.verdict != DriveVerdict.FAILING, r.toString())
        assertEquals(49_963L, r.powerOnHours)
    }

    @Test
    fun `a Seagate's hours come from the header, not the raw value that packs more than hours`() {
        val r = assertNotNull(SmartScreens.parse(text("crystaldiskinfo_de_seagate_hours"), 2))
        assertEquals(26_488L, r.powerOnHours)
    }

    @Test
    fun `a sector count under CrystalDiskInfo's Gut is a misread zero`() {
        val misread = text("crystaldiskinfo_de_monitor_photo_clean").replace(
            Regex("""(Aktuell ausstehende Sektoren 100 100 0 )000000000000"""), "$1000000000900",
        )
        val r = assertNotNull(SmartScreens.parse(misread, 2))
        assertEquals(DriveVerdict.UNREADABLE, r.verdict)
    }

    @Test
    fun `a terminal's slashed zeros read as 9 are no pending sectors`() {
        val r = assertNotNull(SmartScreens.parse(readings("smartctl_slashed_zero_clean"), 2))
        assertTrue(r.verdict != DriveVerdict.FAILING, r.toString())
        assertEquals(33_888L, r.powerOnHours)
    }

    @Test
    fun `a dark terminal turned over reads smartctl's rows`() {
        val r = assertNotNull(SmartScreens.parse(readings("smartctl_dark_clean"), 5))
        assertEquals(DriveVerdict.CLEAN, r.verdict)
        assertEquals(13_461L, r.powerOnHours)
    }

    @Test
    fun `Proxmox's dialog shows the raw value first, and pending sectors there fail the drive`() {
        val r = assertNotNull(SmartScreens.parse(readings("proxmox_failing"), 3))
        assertEquals(DriveVerdict.FAILING, r.verdict)
        assertEquals(15L, r.pending)
        assertEquals(37_506L, r.powerOnHours)
    }

    @Test
    fun `a French CrystalDiskInfo is read by its French names`() {
        val r = assertNotNull(SmartScreens.parse(readings("crystaldiskinfo_fr_clean"), 3))
        assertEquals(DriveVerdict.CLEAN, r.verdict)
        assertEquals(43_602L, r.powerOnHours)
    }

    @Test
    fun `Hard Disk Sentinel's rows put the threshold first and a status word before the raw value`() {
        val r = assertNotNull(SmartScreens.parse(readings("hdsentinel_clean"), 1))
        assertEquals(DriveVerdict.CLEAN, r.verdict)
    }

    @Test
    fun `a raw value written with 0x before it is read`() {
        val r = assertNotNull(SmartScreens.parse(readings("hex_prefix_clean"), 4))
        assertEquals(DriveVerdict.CLEAN, r.verdict)
    }

    @Test
    fun `a dark Scrutiny read twice keeps the helium mark only one reading saw`() {
        val r = assertNotNull(SmartScreens.parse(readings("scrutiny_dark_helium_two_readings"), 2))
        assertEquals(DriveVerdict.FAILING, r.verdict)
        assertTrue(r.findings.any { "Helium 19" in it }, r.findings.toString())
    }

    @Test
    fun `helium is found where the OCR swapped its letters`() {
        val r = assertNotNull(SmartScreens.parse(text("scrutiny_helium_misspelt"), 2))
        assertEquals(DriveVerdict.FAILING, r.verdict)
    }
}
