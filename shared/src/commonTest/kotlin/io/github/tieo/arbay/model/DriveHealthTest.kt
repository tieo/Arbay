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
        assertTrue(looksLikeADrive("Western Digital DC HC510 10TB 7,2K SATA 6G 512e AF SIE 256MB - HUH721010ALE604"))
        assertTrue(looksLikeADrive("Western Digital WD Caviar Green 1TB 3,5\" SATA 32MB Cache WD10EADS"))
        assertTrue(looksLikeADrive("Seagate ST8000DM004 8TB"))
        assertFalse(looksLikeADrive("Google Pixel 9 Pro XL 256 GB Hazel"))
        assertFalse(looksLikeADrive("Stuhl ST1000 Holz"))
    }
}
