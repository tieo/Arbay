package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.ebayGallery
import io.github.tieo.arbay.crawler.ebayStock
import org.jsoup.Jsoup
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

    @Test
    fun `a sold-out listing is read from the page's data, its words still showing a quantity box`() {
        assertEquals(0 to false, ebayStock("Stückzahl: 113 verkauft", """{"outOfStock":true,"quantityInput":{}}"""))
    }

    @Test
    fun `the gallery is the carousel's photos once each at zoom size, not the other items' photos`() {
        val doc = Jsoup.parse(javaClass.getResource("/fixtures/ebay_item_gallery.html")!!.readText())
        assertEquals(
            listOf("VesAAeSwkOVq0NdX", "z~cAAeSwBlBq0Ndb", "P3cAAeSw8JVq0Nde").map { "https://i.ebayimg.com/images/g/$it/s-l1600.webp" },
            ebayGallery(doc),
        )
    }
}
