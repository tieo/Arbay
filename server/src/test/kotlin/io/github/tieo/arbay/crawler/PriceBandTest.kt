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
    fun `TruckScout24 pages through the band and stops at a short page`() = runBlocking {
        val asked = mutableListOf<Int>()
        val crawler = TruckScout24Crawler { _, _, page ->
            asked += page
            val offset = (page - 1) * 25
            (0 until if (page == 1) 25 else 10).map { i ->
                val id = "${offset + i}"
                io.github.tieo.arbay.model.Listing(
                    id = "TRUCKSCOUT24:$id", platformId = io.github.tieo.arbay.model.PlatformId.TRUCKSCOUT24, externalId = id,
                    url = "https://www.truckscout24.de/tsp/$id", title = "VW Crafter $id", price = Money((10_000 + offset + i) * 100L, Currency.EUR),
                    scrapedAt = kotlin.time.Instant.fromEpochSeconds(0),
                )
            }
        }
        val found = crawler.search(crafter)
        assertEquals(35, found.size)
        assertEquals(listOf(1, 2), asked, "a short second page is the last")
    }

    @Test
    fun `Kleinanzeigen goes on from the last price read when its pages run out while still full`() = runBlocking {
        // A market that holds 30 ads at 10,000 € and up in 1,000 € steps, sorted cheapest first,
        // and serves two pages of ten of any answer before repeating the second.
        val asked = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            val url = request.url.toString()
            asked += url
            val floor = Regex("""preis:(\d+):""").find(url)!!.groupValues[1].toInt()
            val page = (Regex("""seite:(\d+)""").find(url)?.groupValues?.get(1)?.toInt() ?: 1).coerceAtMost(2)
            val prices = (0 until 30).map { 10_000 + it * 1_000 }.filter { it >= floor }.drop((page - 1) * 10).take(10)
            respond(kaPage(prices), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
        })
        val vans = SearchQuery(
            text = "Volkswagen Crafter", category = MarketGroup.VEHICLES, maxPages = 2,
            minPrice = Money(10_000_00, Currency.EUR), maxPrice = Money(30_990_00, Currency.EUR),
        )
        val found = KleinanzeigenCrawler(client).search(vans)
        val nutzfahrzeuge = asked.filter { "c276" in it }
        assertEquals((0 until 30).map { 10_000_00L + it * 1_000_00 }.toSet(), found.map { it.price.amount }.toSet())
        assertTrue(nutzfahrzeuge.any { "preis:29000:30990" in it }, "the last answer starts at the dearest price read: $nutzfahrzeuge")
    }

    private fun kaPage(prices: List<Int>): String = buildString {
        append("<html><head><title>Kleinanzeigen</title></head><body>")
        prices.forEach { eur ->
            val id = 3_500_000_000L + eur
            append("""<article data-adid="$id"><a href="/s-anzeige/volkswagen-crafter/$id-276-1">Volkswagen Crafter $eur</a>""")
            append("""<p>${"%,d".format(java.util.Locale.GERMANY, eur)} €</p><p>10115 Berlin</p></article>""")
        }
        append("<p>").append("Nutzfahrzeuge ".repeat(60)).append("</p></body></html>")
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
