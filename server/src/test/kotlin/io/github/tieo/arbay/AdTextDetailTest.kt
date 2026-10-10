package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.VintedDeCrawler
import io.github.tieo.arbay.crawler.WillhabenCrawler
import io.github.tieo.arbay.model.PlatformId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The seller's own text, read off willhaben's and Vinted's ad pages in the shape they serve. */
class AdTextDetailTest {

    private val willhabenPage = """<html><body><script id="__NEXT_DATA__" type="application/json">
        {"props":{"pageProps":{"advertDetails":{"id":"1","description":"Titel",
        "attributes":{"attribute":[{"name":"PRICE","values":["199"]},
        {"name":"DESCRIPTION","values":["Beispiel SSD 2TB:<br/>Voll funktionsfähig.<br/><br/>Versand möglich."]}]},
        "advertAddressDetails":{"postCode":"1080","postalName":"Wien, 08. Bezirk, Josefstadt","country":"Österreich"},
        "advertStatus":{"id":"active"}}}}}</script></body></html>"""

    private val vintedPage = """<html><body><div class="details"><div itemprop="description">
        <span>  Top Zustand, auf Funktion getestet  </span>
        </div></div></body></html>"""

    @Test
    fun `willhaben's description and place come from the page data`() {
        val crawler = CrawlerRegistry.crawlerFor(PlatformId.WILLHABEN) as WillhabenCrawler
        val detail = crawler.parseDetail(willhabenPage)!!
        assertEquals("Beispiel SSD 2TB:\nVoll funktionsfähig.\nVersand möglich.", detail.description)
        assertEquals("Wien", detail.location?.city)
        assertEquals("1080", detail.location?.zip)
    }

    @Test
    fun `Vinted's description is the element marked as one`() {
        val crawler = CrawlerRegistry.crawlerFor(PlatformId.VINTED_DE) as VintedDeCrawler
        assertEquals("Top Zustand, auf Funktion getestet", crawler.parseDetail(vintedPage)?.description)
    }
}
