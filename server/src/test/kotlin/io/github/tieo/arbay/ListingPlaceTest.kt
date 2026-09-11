package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.EbayDeCrawler
import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.crawler.VehicleTextParser
import io.github.tieo.arbay.crawler.area
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.DropReason
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a listing is, how wide it is, and who took it off the screen.
 *
 * Each of these was wrong on a real screen: eBay listings arrived with no location while their own
 * item page states one, a search that never asked for a place still fenced itself to 30 km, and
 * twelve vans blocked by the reader's own word appeared under "not one offer", which reads as the
 * market having sent junk.
 */
class ListingPlaceTest {

    private fun listing(title: String, id: String = "x") = Listing(
        id = id, platformId = PlatformId.EBAY_DE, externalId = id,
        url = "https://example.invalid/$id", title = title,
        price = Money(1_000_00, Currency.EUR), scrapedAt = Clock.System.now(),
    )

    @Test
    fun `eBay states the place on the item page`() {
        val html = """{"shipping":{},"itemLocation":{"_type":"LabelsValues","labels":[{"_type":""" +
            """"TextualDisplay","textSpans":[{"_type":"TextSpan","text":"Standort"}]}],"values":""" +
            """[{"_type":"TextualDisplay","textSpans":[{"_type":"TextSpan","text":"Hamburg, """ +
            """Deutschland"}]}]},"getRates":{}}"""
        val place = EbayDeCrawler.itemLocation(html)
        assertEquals("Hamburg", place?.city)
        assertEquals("Deutschland", place?.country)
    }

    @Test
    fun `a page without the block states no place`() {
        assertNull(EbayDeCrawler.itemLocation("""{"price":{"value":"EUR 12,00"}}"""))
    }

    @Test
    fun `a search that names no place covers everywhere`() {
        // The radius used to default to 30 km, so every stored search that predates the field
        // decoded to a 30 km fence and threw away most of what the markets sent.
        val query = SearchQuery(text = "vw crafter", category = MarketGroup.VEHICLES)
        assertEquals(0, query.radiusKm)
        assertNull(query.area("DE"))
    }

    @Test
    fun `a blocked word is reported as the reader's own word`() {
        val query = SearchQuery(
            text = "volkswagen crafter", category = MarketGroup.VEHICLES,
            excludeKeywords = listOf("pritsche"),
        )
        val listings = listOf(
            listing("Volkswagen Crafter Pritsche 35 lang DOKA", id = "a"),
            listing("Volkswagen Crafter Kasten 35 mittellang", id = "b"),
        )
        val partitioned = RelevanceFilter.partition(listings, query)
        assertEquals(listOf("b"), partitioned.kept.map { it.id })
        assertEquals(DropReason.BLOCKED_WORD, partitioned.dropped.single().reason)
    }

    @Test
    fun `every way 72 van ads wrote a wheelbase down`() {
        // Each of these is a line off a real AutoScout24 advert. 40 of 71 readable pages state a
        // wheelbase at all, always inside the equipment prose and never as a field of the site's
        // own, which is why it is read off the detail page and off the seller's own text.
        val stated = mapOf(
            "Parklichtschaltung, Radstand 3250 mm, Reifen-Reparaturkit" to 3250,
            "Federung und Dämpfung verstärkt Radstand 3.640 mm (MR)" to 3640,
            "Motor 2,0 Ltr. - 130 kW TDI\nRadstand 4490 mm\nSchadstoffarm" to 4490,
            "Produktionsstätte: Otosan Radstand 3300 mm Radvollabdeckung" to 3300,
            "Motor 2,1 Ltr. - 95 kW CDI KAT (2143 ccm), Radstand 3665 mm" to 3665,
            "Radabstand: 4490 mm" to 4490,
            "Radstand lang (LR) - 4.490 mm" to 4490,
            "Achsabstand 3,64 m" to 3640,
            "Radstand 305 cm" to 3050,
            "Wheelbase 3250mm" to 3250,
        )
        stated.forEach { (line, mm) ->
            assertEquals(mm, VehicleTextParser.parseWheelbaseMm(line), "read off: $line")
        }
        // Stated without a number, and a number that belongs to something else.
        assertNull(VehicleTextParser.parseWheelbaseMm("Langer Radstand !!!"))
        assertNull(VehicleTextParser.parseWheelbaseMm("Radstand verstärkt, Motor 2,1 Ltr. - 120 kW"))
        assertNull(VehicleTextParser.parseWheelbaseMm(""""wheelBase":null,"costModel":null,4"""))
    }

    @Test
    fun `a wheelbase is read where the ad writes one`() {
        val stated = VehicleTextParser.parse("VW Crafter, Radstand 3640 mm, Klima")
        assertEquals(3640, stated?.wheelbaseMm)
        assertEquals(3250, VehicleTextParser.parse("Radstand: 3.250 mm")?.wheelbaseMm)
        // A number that is not a wheelbase must not become one.
        assertNull(VehicleTextParser.parse("VW Crafter 138.000 km, 130 kW")?.wheelbaseMm)
        assertTrue(VehicleTextParser.parse("Radstand 300 mm")?.wheelbaseMm == null)
    }
}
