package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.SearchQuery
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A search with a price ceiling reads every offer in its band on the markets that sort by price.
 *
 * Live, 2026-10-16: TruckScout24 was read as one page of 25 in its own relevance order and
 * mobile.de as three pages of 20. Once the Crafter search stopped asking for a minimum power, its
 * band held 188 vans on TruckScout24, and the best van found four days earlier (19,971 €, 177 PS)
 * was no longer among the 25 read: it was still online, and the search had stopped seeing it.
 */
class PriceBandTest {
    private val crafter = SearchQuery(text = "VW Crafter", category = MarketGroup.VEHICLES, maxPrice = Money(30_990_00, Currency.EUR))

    @Test
    fun `a search with a ceiling reads its whole band, one that names a count keeps it`() {
        assertEquals(WHOLE_BAND_RESULTS, crafter.wholePriceBand().resultCap())
        assertEquals(DEEP_SEARCH_PAGES, crafter.wholePriceBand().pageLimit())
        assertEquals(200, crafter.copy(maxResults = 200).wholePriceBand().resultCap())
        val noCeiling = SearchQuery(text = "VW Crafter", category = MarketGroup.VEHICLES)
        assertEquals(noCeiling, noCeiling.wholePriceBand())
    }

    @Test
    fun `mobile_de reads as many pages as the offers wanted fill`() {
        val mobile = MobileDeCrawler(HttpClient(MockEngine { respond("") }))
        assertEquals(3, mobile.pagesFor(60, DEEP_SEARCH_PAGES))
        assertEquals(DEEP_SEARCH_PAGES, mobile.pagesFor(WHOLE_BAND_RESULTS, DEEP_SEARCH_PAGES))
    }

    @Test
    fun `TruckScout24 pages through the band cheapest first and stops at a short page`() = runBlocking {
        val asked = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            asked += url
            val page = Regex("""[?&]page=(\d+)""").find(url)?.groupValues?.get(1)?.toInt() ?: 1
            val cards = if (page == 1) 25 else 10
            respond(page(offset = (page - 1) * 25, count = cards), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val found = TruckScout24Crawler(client).search(crafter)
        assertEquals(35, found.size)
        assertEquals(2, asked.size, "a short second page is the last: $asked")
        assertTrue(asked.all { "sort-field=preis&sort-direction=asc" in it }, "$asked")
        assertTrue("page=2" in asked[1])
    }

    private fun page(offset: Int, count: Int): String = buildString {
        append("<html><head><title>Transporter</title></head><body>")
        repeat(count) { i ->
            val id = 22_900_000 + offset + i
            append("""<section data-listing-id="$id"><a href="/tsp/ts-$id"><div data-grid="title">Kastenwagen hoch VWCrafter 35 L3H3 Automatik $id</div></a>""")
            append("""<div data-grid="price">${15_000 + (offset + i) * 100} € brutto</div><div data-grid="location">DE-41063 Mönchengladbach</div></section>""")
        }
        append("<p>").append("Gebrauchte Transporter ".repeat(60)).append("</p></body></html>")
    }
}
