package io.github.tieo.arbay.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs in the browser's JavaScript too, whose regular expressions refuse some Java syntax. */
class PickupOnlyTest {
    @Test
    fun `a seller who writes that nothing is sent is read as pickup only`() {
        assertTrue(saysPickupOnly("Nur Abholung, da mir Festplattenversand zu riskant ist."))
        assertTrue(saysPickupOnly("Versand biete ich aufgrund der fragilen Natur von Festplatten nicht an."))
        assertTrue(saysPickupOnly("Ich versende wegen des Versandrisikos keine Festplatten mehr."))
        assertTrue(saysPickupOnly("Ist noch zu haben bis ich die Anzeige lösche. Nur an \"Selbstabholer\""))
        assertTrue(saysPickupOnly("Kein Versand."))
    }

    @Test
    fun `shipping offered or only limited is still shipping`() {
        assertFalse(saysPickupOnly("Versand oder Abholung möglich."))
        assertFalse(saysPickupOnly("Abholung in Halle möglich, Versand zuzüglich 7 €."))
        assertFalse(saysPickupOnly("Ich versende nicht ins Ausland."))
        assertFalse(saysPickupOnly("Versand nach Absprache."))
    }
}
