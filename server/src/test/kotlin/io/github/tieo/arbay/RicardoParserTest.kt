package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CarFilterEngine
import io.github.tieo.arbay.crawler.RicardoCrawler
import io.github.tieo.arbay.model.CarFilters
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.VehicleCondition
import io.github.tieo.arbay.testing.offlineClient
import io.ktor.client.*
import kotlin.test.*

/**
 * ricardo.ch renders results into the Next.js RSC stream, so the parser has to rebuild the
 * payload from `self.__next_f.push([1,"…"])` chunks before it can read the articles array.
 */
class RicardoParserTest {

    private val crawler = RicardoCrawler(offlineClient())

    /** Wrap a payload the way the page does: a JSON-encoded string inside a push call. */
    private fun rscHtml(vararg payloads: String): String = payloads.joinToString("\n") { payload ->
        val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"")
        """<script>self.__next_f.push([1,"$escaped"])</script>"""
    }

    private val articlesPayload = """
        3:{"facetCountAccuracy":"exact","articles":[
        {"id":"1324128308","title":"VW Crafter Manuell 2.0TDI 140PS","buyNowPrice":null,"bidPrice":8209,
         "image":"https://img.ricardostatic.ch/images/abc/t_265x200/vw-crafter","conditionKey":"acceptable",
         "shipping":[{"key":"get_by_buyer","cost":0,"zipCode":"8820","city":"Wädenswil"}],"brand":"VW"},
        {"id":"1323585366","title":"Wohnmobil VW Crafter 4x4","buyNowPrice":117000,"bidPrice":null,
         "image":"https://img.ricardostatic.ch/images/def/t_265x200/wohnmobil","conditionKey":"used",
         "shipping":[{"key":"pickup","cost":0,"zipCode":"3000","city":"Bern"}],"brand":"VW"}
        ]}
    """.trimIndent().replace("\n", "")

    @Test
    fun `parses articles out of the RSC chunk stream`() {
        val listings = crawler.parse(rscHtml(articlesPayload))
        assertEquals(2, listings.size)

        val auction = listings.first()
        assertEquals("RICARDO:1324128308", auction.id)
        assertEquals("VW Crafter Manuell 2.0TDI 140PS", auction.title)
        assertEquals("https://www.ricardo.ch/de/a/1324128308/", auction.url)
        assertEquals("Wädenswil", auction.location?.city)
        assertEquals("CH", auction.location?.country)
    }

    @Test
    fun `prices are whole Swiss francs`() {
        val listings = crawler.parse(rscHtml(articlesPayload))
        // The auction listing has no buy-now price, so the current bid stands in.
        assertEquals(8209 * 100L, listings[0].price.amount)
        assertEquals(Currency.CHF, listings[0].price.currency)
        // A buy-now price wins over the bid.
        assertEquals(117000 * 100L, listings[1].price.amount)
    }

    @Test
    fun `condition from the site is treated as verified`() {
        val listings = crawler.parse(rscHtml(articlesPayload))
        assertEquals(VehicleCondition.USED, listings[1].vehicle?.condition)
    }

    @Test
    fun `payload split across several chunks is rejoined`() {
        val half = articlesPayload.length / 2
        val listings = crawler.parse(
            rscHtml(articlesPayload.substring(0, half), articlesPayload.substring(half)),
        )
        assertEquals(2, listings.size)
    }

    @Test
    fun `a listing without any price is dropped`() {
        val payload = """3:{"articles":[{"id":"1","title":"VW Crafter","buyNowPrice":null,"bidPrice":null}]}"""
        assertTrue(crawler.parse(rscHtml(payload)).isEmpty())
    }

    @Test
    fun `html without an RSC stream yields nothing`() {
        assertTrue(crawler.parse("<html><body>no results</body></html>").isEmpty())
    }

    @Test
    fun `parts and accessories are dropped by product type`() {
        // A Crafter search on a general marketplace returns as many accessories as vehicles.
        val payload = """3:{"articles":[
            {"id":"1","title":"VW Crafter Gummifussmatten vorn","buyNowPrice":30,"productTypeKey":"car_mat"},
            {"id":"2","title":"VW Bus Schlüsselanhänger","buyNowPrice":5,"productTypeKey":"keychain"},
            {"id":"3","title":"Reparatursatz VW Crafter","buyNowPrice":20,"productTypeKey":"auto_part"},
            {"id":"4","title":"VW Crafter 2.0 TDI","buyNowPrice":15000,"productTypeKey":"commercial_vehicle"}
        ]}""".trimIndent().replace("\n", "")

        val listings = crawler.parse(rscHtml(payload), vehiclesOnly = true)
        assertEquals(1, listings.size)
        assertEquals("VW Crafter 2.0 TDI", listings.single().title)
    }

    @Test
    fun `campers and trucks count as vehicles`() {
        val payload = """3:{"articles":[
            {"id":"1","title":"VW Crafter Campervan","buyNowPrice":40000,"productTypeKey":"caravan"},
            {"id":"2","title":"VW Crafter Kipper","buyNowPrice":22000,"productTypeKey":"truck"}
        ]}""".trimIndent().replace("\n", "")
        assertEquals(2, crawler.parse(rscHtml(payload), vehiclesOnly = true).size)
    }

    @Test
    fun `a product search keeps non-vehicle product types`() {
        // "Parkettschleifmaschine" is not a car query, so the grinding machine must survive.
        val payload = """3:{"articles":[{"id":"1","title":"Parkettschleifmaschine Laegler","buyNowPrice":1200,"productTypeKey":"grinding_machine"}]}"""
        assertEquals(1, crawler.parse(rscHtml(payload)).size)
    }

    @Test
    fun `a partition leading the title is a part, but equipment on a van is not`() {
        val partition = listOf(
            "Schiebetür/ Trennwand mit tür Vw Crafter, MAN TGE",
            "Delta Höherlegungs Kit zu VW Crafter und MAN TGE",
        ).map { carListing(it) }
        val van = carListing("VW Crafter 35 lang mit Trennwand")

        val kept = CarFilterEngine.apply(partition + van, CarFilters())
        assertEquals(listOf("VW Crafter 35 lang mit Trennwand"), kept.map { it.title })
    }

    /** A ricardo listing with no structured specs, which is what the search payload gives. */
    private fun carListing(title: String) = io.github.tieo.arbay.model.Listing(
        id = "RICARDO:$title",
        platformId = io.github.tieo.arbay.model.PlatformId.RICARDO,
        externalId = title,
        url = "https://www.ricardo.ch/de/a/1/",
        title = title,
        price = io.github.tieo.arbay.model.Money(500_000, Currency.CHF),
        scrapedAt = kotlinx.datetime.Clock.System.now(),
    )

    @Test
    fun `an unstated product type is kept`() {
        // Unknown must not silently discard a real listing.
        val payload = """3:{"articles":[{"id":"9","title":"VW Crafter 35","buyNowPrice":12000}]}"""
        assertEquals(1, crawler.parse(rscHtml(payload)).size)
    }
}
