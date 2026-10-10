package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.EbayDeCrawler
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals

class EbayDetailTextTest {

    // The markup of itm.ebaydesc.com's description page and of the item page's seller card, as
    // eBay serves them, with a made-up seller and text.
    private val descriptionPage = """<!doctype html><html lang=de><head><title>eBay</title><style>body{font-family:'Market Sans'}</style>
        <script>var x=1;</script></head><body><script>track()</script>
        <div data-marko-key="@container s0-2-12" class=x-item-description-child data-testid=x-item-description-child>Verkaufe meine SSD.<br><br>Lief ein Jahr, keine Fehler.<br>* Bauform: M.2 2280<br>* Protokoll: NVMe 1.4</div></body></html>"""

    private val sellerCard = """<div class="vim x-sellercard-atf" data-testid="x-sellercard-atf"><div><span>S</span>
        <a href="https://www.ebay.de/sch/beispiel/m.html">beispiel</a> <span>(1.532)</span><span>Privat</span>
        <span>100% positive Bewertungen</span></div></div>"""

    @Test
    fun `the seller's description is read line by line without the page around it`() {
        assertEquals(
            "Verkaufe meine SSD.\nLief ein Jahr, keine Fehler.\n* Bauform: M.2 2280\n* Protokoll: NVMe 1.4",
            EbayDeCrawler.descriptionText(descriptionPage),
        )
    }

    @Test
    fun `the feedback count is read off the seller card, thousands included`() {
        assertEquals(1532, EbayDeCrawler.sellerFeedback(Jsoup.parse(sellerCard)))
    }
}
