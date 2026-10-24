package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.WillhabenCrawler
import io.github.tieo.arbay.model.AdState
import io.github.tieo.arbay.testing.offlineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whether a willhaben ad is still up, as its page says it. The pages are cut from a WD Blue SN570
 * on offer and from the search a sold WD Blue SN580 redirects to.
 */
class WillhabenAdStateTest {

    private val crawler = WillhabenCrawler(offlineClient())

    private fun page(pageProps: String) =
        """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":$pageProps},"page":"/x"}</script></html>"""

    @Test
    fun `an ad that is up carries the status active`() {
        val html = page("""{"advertDetails":{"id":"988551891","advertStatus":{"id":"active","description":"aktiv","statusId":50}}}""")
        assertEquals(AdState.LIVE, crawler.adStateOf(html))
    }

    @Test
    fun `a sold ad redirects to its category search naming the expired ad`() {
        val html = page("""{"searchResult":{},"searchTerms":null,"fromExpiredAdId":"1983211574","defaultViewMode":"LIST"}""")
        assertEquals(AdState.DELETED, crawler.adStateOf(html))
    }

    @Test
    fun `a page without the ad says nothing`() {
        assertNull(crawler.adStateOf("<html><title>willhaben</title></html>"))
    }
}
