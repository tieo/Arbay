package io.github.tieo.arbay

import io.github.tieo.arbay.markets.WebCrawler
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the web market decides itself: which pages to read and how their offers are kept once each.
 *  The pages' reading is Neuland's and tested there. */
class WebMarketTest {
    @Test
    fun `one shop's country sites listing one offer show it once`() {
        fun web(url: String, title: String = "SO-DIMM Interposer Daughterboard", cents: Long = 5680) = io.github.tieo.arbay.model.Listing(
            id = "WEB:$url", platformId = io.github.tieo.arbay.model.PlatformId.WEB, externalId = url, url = url, title = title,
            price = Money(cents, Currency.EUR), scrapedAt = kotlin.time.Clock.System.now())
        val kept = WebCrawler.oneShopOnce(listOf(
            web("https://www.compuram.de/zubehoer/adapter/so-dimm-interposer-daughterboard/rn12h-m11dcb.htm"),
            web("https://www.compuram.de/it/accessori/adattatore/so-dimm-interposer-daughterboard/rn12h-m11dcb.htm"),
            web("https://www.compuram.biz/fr/accessories/adapter/so-dimm-interposer-daughterboard/rn12h-m11dcb.htm"),
            // One site's two variants of one title and price stay two.
            web("https://www.compuram.de/zubehoer/adapter/so-dimm-interposer-daughterboard/rn12h-m11dcc.htm"),
            web("https://www.compuram.de/zubehoer/adapter/other.htm", title = "Anderes Board"),
        ))
        assertEquals(3, kept.size)
        assertEquals("https://www.compuram.de/zubehoer/adapter/so-dimm-interposer-daughterboard/rn12h-m11dcb.htm", kept.first().url)
    }

    @Test
    fun `a market is known by its name in every country`() {
        assertEquals("amazon", WebCrawler.siteName("amazon.co.jp"))
        assertEquals("amazon", WebCrawler.siteName("smile.amazon.de"))
        assertEquals("ebay", WebCrawler.siteName("ebay.com.au"))
        assertEquals("alternate", WebCrawler.siteName("alternate.de"))
    }

    @Test
    fun `a site given up on stays given up until it gets a recipe`() {
        val site = "given-up-test.de"
        io.github.tieo.arbay.markets.SiteGaps.giveUp(site)
        try {
            assertTrue(io.github.tieo.arbay.markets.SiteGaps.isGivenUp(site))
        } finally {
            io.github.tieo.arbay.markets.SiteGaps.takeBack(site)
        }
        assertFalse(io.github.tieo.arbay.markets.SiteGaps.isGivenUp(site))
    }

    @Test
    fun `a site's front page is told from its other pages`() {
        assertTrue(WebCrawler.isFrontPage("https://www.grills.de/"))
        assertTrue(WebCrawler.isFrontPage("https://www.grills.de"))
        assertFalse(WebCrawler.isFrontPage("https://www.grills.de/search?sSearch=Weber"))
        assertFalse(WebCrawler.isFrontPage("https://www.grills.de/?sSearch=Weber"))
        assertFalse(WebCrawler.isFrontPage("https://www.grills.de/weber-genesis"))
    }

    @Test
    fun `a page listed through Google's translating proxy is read at the shop's own address`() {
        assertEquals("https://www.amazon.it/Levigatrici-lamiera/s?rh=n:3121001031,p_6:A20FPJ7ZUTLVQJ",
            WebCrawler.unproxied("https://www-amazon-it.translate.goog/Levigatrici-lamiera/s?rh=n:3121001031,p_6:A20FPJ7ZUTLVQJ&_x_tr_sl=it&_x_tr_tl=de&_x_tr_hl=de&_x_tr_pto=sc"))
        assertEquals("https://www.dielen-schleifen.de/miete?x=1",
            WebCrawler.unproxied("https://www-dielen--schleifen-de.translate.goog/miete?_x_tr_sl=de&x=1"))
        assertEquals("https://www.e-hoi.de/", WebCrawler.withoutSession("https://www.e-hoi.de/?msockid=063548ec2ade6dcd3b695f112b266c95"))
        assertEquals("https://de.aliexpress.com/item/1005012926471402.html", WebCrawler.withoutSession(
            "https://de.aliexpress.com/item/1005012926471402.html?algo_pvid=0d81ff47&algo_exp_id=0d81ff47-0&pdp_ext_f=%7B%7D&pdp_npi=6%40dis%21EUR&curPageLogUid=SSjsVqxstBt1&utparam-url=scene%3Asearch"))
    }

    @Test
    fun `an offer's address loses the session and tracking that make it a new one each visit`() {
        assertEquals("https://www.gerach-gruppe.de/mietpark/fraes-und-schleiftechnik/bodenbearbeitungsmaschinen-mieten/",
            WebCrawler.withoutSession("https://www.gerach-gruppe.de/mietpark/fraes-und-schleiftechnik/bodenbearbeitungsmaschinen-mieten/?utm_source=qwant&utm_medium=referral&utm_campaign=ai_flash"))
        assertEquals("https://www.pearl.de/a-ZX4189-1162.shtml?query=x",
            WebCrawler.withoutSession("https://www.pearl.de/a-ZX4189-1162.shtml;jsessionid=n073D1260CCA?query=x&gclid=abc"))
        assertEquals("https://www.voelkner.de/index.php?mp=products&products_id=12147583",
            WebCrawler.withoutSession("https://www.voelkner.de/index.php?mp=products&products_id=12147583&utm_source=google&gclid=EAIa"))
        // Zen Cart (aicasanitaer.de) writes the visitor's session into every product link.
        assertEquals("https://aicasanitaer.de/index.php?main_page=product_info&cPath=6_33&products_id=5296",
            WebCrawler.withoutSession("https://aicasanitaer.de/index.php?main_page=product_info&cPath=6_33&products_id=5296&zenid=65fab3ac8995c35f97c86f980ae23fc5"))
    }

}
