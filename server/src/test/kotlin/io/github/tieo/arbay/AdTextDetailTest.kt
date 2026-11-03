package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.VintedDeCrawler
import io.github.tieo.arbay.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The seller's own text, read off Vinted's ad pages in the shape they serve. */
class AdTextDetailTest {

    private val vintedPage = """<html><body><div class="details"><div itemprop="description">
        <span>  Top Zustand, auf Funktion getestet  </span>
        </div></div></body></html>"""

    @Test
    fun `Vinted's description is the element marked as one`() {
        val crawler = CrawlerRegistry.crawlerFor(PlatformId.VINTED_DE) as VintedDeCrawler
        assertEquals("Top Zustand, auf Funktion getestet", crawler.parseDetail(vintedPage)?.description)
    }
}
