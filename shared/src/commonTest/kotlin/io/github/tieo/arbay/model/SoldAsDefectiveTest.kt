package io.github.tieo.arbay.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoldAsDefectiveTest {

    @Test
    fun `a seller saying the thing is broken is read as that`() {
        // Kleinanzeigen, live: a 1TB drive at 50 euro, broken by its own text only.
        assertTrue(Condition.soldAsDefective("SSD M2 NVME 1TB 1000GB CRUCIAL", "Wird im mein pc nicht erkannt wird als defekt verkauft"))
        assertTrue(Condition.soldAsDefective("WD_BLACK SN770 int. M.2 NVMe SSD 2TB DEFEKT", null))
        assertTrue(Condition.soldAsDefective("3 Defekte HDD Festplatten insgesamt fast 5TB", null))
        assertTrue(Condition.soldAsDefective("8TB Seagate HDD Festplatte", "☆ 8TB Seagate HDD Festplatte ( Defekt )\n☆ Die Festplatte läuft nicht an."))
        assertTrue(Condition.soldAsDefective("Seagate 4TB", "Platte läuft nicht mehr an, für Datenrettung"))
        assertTrue(Condition.soldAsDefective("Samsung 970 EVO 1TB", "Für Bastler, startet nicht"))
        // Kleinanzeigen, live: two hard drives for a 4TB and a 10TB search, broken in their text.
        assertTrue(Condition.soldAsDefective("Synology HDD 4TB 3.5 Zoll SATA Festplatte", "Ohne Funktion Defekt für Ersatzteilegewinnung"))
        assertTrue(Condition.soldAsDefective("Western Digital WD Gold 10TB SATA HDD WD102KRYZ Enterprise Serie",
            "Die HDD wurde mit der WD-Software gelöscht, leider wird nun die HDD nicht mehr erkannt und lässt sich nicht initialisieren."))
    }

    @Test
    fun `a word that only mentions a defect does not make the thing broken`() {
        assertFalse(Condition.soldAsDefective("Samsung 990 PRO 2TB nicht defekt", null))
        assertFalse(Condition.soldAsDefective("SK hynix PC801 1TB", "Ausgebaut aus einem defekten Laptop, die SSD läuft einwandfrei."))
        assertFalse(Condition.soldAsDefective("Lexar NM610 PRO 2TB", "Keine Defekte, SMART PASSED"))
    }
}
