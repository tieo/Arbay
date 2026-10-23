package io.github.tieo.arbay.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs in the browser's JavaScript too, whose regular expressions refuse some Java syntax. */
class DriveHealthTest {
    @Test
    fun `a drive's title is told from anything else`() {
        assertTrue(looksLikeADrive("Seagate Ironwolf 12TB NAS HDD Festplatte"))
        assertTrue(looksLikeADrive("Toshiba MG09 18TB SATA Festplatten"))
        assertTrue(looksLikeADrive("WD Red 6TB NAS"))
        assertFalse(looksLikeADrive("Google Pixel 9 Pro XL 256 GB Hazel"))
    }
}
