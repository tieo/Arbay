package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.ebayStock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EbayStockTest {
    @Test
    fun `the words beside the quantity say how many are left`() {
        assertEquals(3 to false, ebayStock("Anzahl: 1 3 verfügbar 12 verkauft"))
        assertEquals(10 to true, ebayStock("Anzahl: 1 Mehr als 10 verfügbar 45 verkauft"))
        assertEquals(1 to false, ebayStock("Letzter Artikel 7 verkauft"))
        assertNull(ebayStock("Aktuelles Gebot EUR 241,99 5 Gebote"))
    }
}
