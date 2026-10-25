package io.github.tieo.arbay.model

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OffMarketTest {
    @Test
    fun `an ad refusing the market's checkout and messages sends its buyer off it`() {
        val pixel = "✨ IMPORTANTE ✨\n📥 Non leggiamo i messaggi qui.\n🚫 NON effettuiamo vendite tramite wallet Vinted.\n" +
            "💬 Per qualsiasi informazione, foto o per accordi, contattarci esclusivamente su WhatsApp:\n📲 351 253 4079"
        assertNotNull(OffMarket.sign(pixel))
        assertNotNull(OffMarket.sign("Kontakt nur über WhatsApp, hier lese ich keine Nachrichten"))
        assertNotNull(OffMarket.sign("Contact me on Telegram only"))
        assertNotNull(OffMarket.sign("Kein Kauf über Vinted-Zahlung, nur Überweisung"))
    }

    @Test
    fun `whatsapp offered beside the market says nothing`() {
        assertNull(OffMarket.sign("Fragen gerne auch per WhatsApp. Versand oder Abholung möglich."))
        assertNull(OffMarket.sign("Kein Sicher bezahlen, nur Abholung. Kein Versand."))
        assertNull(OffMarket.sign(null))
        assertNull(OffMarket.sign("Handy mit Telegram und WhatsApp eingerichtet, nur 2 Monate alt"))
        assertNull(OffMarket.sign("Nur 2 Monate alt, WhatsApp und Telegram eingerichtet"))
    }
}
