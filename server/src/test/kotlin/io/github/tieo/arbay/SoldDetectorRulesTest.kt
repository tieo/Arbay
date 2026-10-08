package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.SoldDetector
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoldDetectorRulesTest {
    @Test
    fun `an ad saying it is being sold is for sale`() {
        for (d in listOf(
            "Das Smartphone ist 2 Jahre alt und wird verkauft, da ich auf ein neues Smartphone gewechselt habe.",
            "Das Gerät funktioniert einwandfrei und wird aufgrund eines Wechsels verkauft.",
            "Es wird nur verkauft, da ich doch bei Apple bleibe.",
            "Verkauft wird ein Pixel 9 Pro XL. Kann gerne reserviert werden.",
        )) assertFalse(SoldDetector.isSoldByRules("Google Pixel 9 Pro XL 256GB", d), d)
    }

    @Test
    fun `an ad saying it is gone is sold`() {
        assertTrue(SoldDetector.isSoldByRules("Google Pixel 9 Pro XL", "Ist leider bereits verkauft."))
        assertTrue(SoldDetector.isSoldByRules("Google Pixel 9 Pro XL", "Nicht mehr verfügbar!"))
        assertTrue(SoldDetector.isSoldByRules("VERKAUFT Google Pixel 9 Pro XL", ""))
        assertTrue(SoldDetector.isSoldByRules("Google Pixel 9 Pro XL - reserviert", ""))
    }
}
