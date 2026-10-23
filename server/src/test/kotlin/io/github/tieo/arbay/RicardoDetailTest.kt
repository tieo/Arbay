package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.RicardoCrawler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A ricardo item page as the stealth browser reads it, cut down to its Product data and gallery. */
class RicardoDetailTest {
    private val html = javaClass.getResource("/fixtures/ricardo_item.html")!!.readText()

    @Test
    fun `every photo of the gallery is read at its largest, in order`() {
        val d = assertNotNull(RicardoCrawler(CrawlerRegistry.httpClient).parseDetail(html))
        assertEquals(4, d.photos.size)
        assertTrue(d.photos.all { "/t_1800x1350/" in it }, d.photos.toString())
        assertTrue("30dc4016" in d.photos.first())
    }

    @Test
    fun `the description is the seller's plain text`() {
        val d = assertNotNull(RicardoCrawler(CrawlerRegistry.httpClient).parseDetail(html))
        assertTrue(d.description!!.startsWith("Ich verkaufe eine 1TB HDD"), d.description)
        assertTrue("<p>" !in d.description!!)
    }
}
