package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.VintedDeCrawler
import io.github.tieo.arbay.model.AdState
import io.github.tieo.arbay.testing.offlineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Whether a Vinted item is still for sale, as its page says it. The item travels in the page's
 * script with its quotes escaped, as cut here from a sold Samsung 980 Pro and a Lexar on offer.
 */
class VintedAdStateTest {

    private val crawler = VintedDeCrawler(offlineClient())

    @Test
    fun `an item on offer can be bought`() {
        val page = """self.__next_f.push([1,"{\"is_hated\":false,\"can_buy\":true,\"instant_buy\":true,\"is_reserved\":false,\"is_hidden\":false}"])"""
        assertEquals(AdState.LIVE, crawler.adStateOf(page))
    }

    @Test
    fun `a sold item keeps its page and shows a status box instead of the buy button`() {
        val page = """self.__next_f.push([1,"{\"plugins\":[{\"data\":{\"item_id\":\"1\",\"theme\":\"SUCCESS\",\"title\":\"Verkauft\"},""" +
            """\"exposures\":[],\"name\":\"buyer_item_status\"}],\"can_buy\":false,\"instant_buy\":false,\"is_reserved\":false,\"is_hidden\":false}"])"""
        assertEquals(AdState.DELETED, crawler.adStateOf(page))
    }

    @Test
    fun `a reserved item is promised to a buyer and may come back`() {
        val page = """self.__next_f.push([1,"{\"can_buy\":false,\"instant_buy\":false,\"is_reserved\":true,\"is_hidden\":false}"])"""
        assertEquals(AdState.PAUSED, crawler.adStateOf(page))
    }

    @Test
    fun `a page without the item says nothing`() {
        assertNull(crawler.adStateOf("<html><title>Vinted</title></html>"))
    }
}
