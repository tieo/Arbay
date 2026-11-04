package io.github.tieo.arbay.mcp

import io.github.tieo.arbay.model.Delivery
import kotlin.test.Test
import kotlin.test.assertEquals

class DeliveryWordsTest {
    @Test
    fun `a delivery reads as the days its market names`() {
        assertEquals("by Fri 6 Nov", Delivery("bis Fr. 06.11.", latest = "2026-11-06").describe())
        assertEquals("Tue 10 Nov – Thu 12 Nov", Delivery("Lieferung zwischen Di, 10. Nov und Do, 12. Nov", "2026-11-10", "2026-11-12").describe())
        assertEquals("on Fri 27 Nov", Delivery("FREE delivery Fri 27 Nov", "2026-11-27", "2026-11-27").describe())
        assertEquals("in 2–3 days", Delivery("2-3 Tage Lieferung", days = listOf(2, 3)).describe())
        assertEquals("in 1 day", Delivery("Auf Lager, 1 Werktag", days = listOf(1, 1)).describe())
        assertEquals("Lieferung in Kürze", Delivery("Lieferung in Kürze").describe())
    }
}
