package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.EbayDeCrawler
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What eBay's own "new listing" flag did to a title.
 *
 * Off the phone: eight results on one search were titled "New Listing" or "Neues Angebot", with a
 * price, a photo and a bid count, because the flag is a span of its own in front of the title and
 * the first span was what got read. They were then thrown away as placeholder titles, which is the
 * newest listings on the market lost on every search.
 */
class EbayTitleTest {

    private fun titleOf(html: String) =
        EbayDeCrawler.listingTitle(Jsoup.parseBodyFragment(html).body().child(0))

    @Test
    fun `the flag in front of a new listing is not the title`() {
        assertEquals(
            "Crucial 32GB DDR4-3200 SO-DIMM CT32G4SFD832A",
            titleOf(
                """<div class="s-card__title"><span class="LIGHT_HIGHLIGHT">Neues Angebot</span>""" +
                    """<span>Crucial 32GB DDR4-3200 SO-DIMM CT32G4SFD832A</span></div>""",
            ),
        )
    }

    @Test
    fun `the same flag on every eBay that has one`() {
        listOf("New Listing", "Nuova inserzione", "Nueva publicación", "Nouvelle annonce")
            .forEach { badge ->
                assertEquals(
                    "Crucial 32GB SODIMM",
                    titleOf("""<div class="s-card__title"><span>$badge</span><span>Crucial 32GB SODIMM</span></div>"""),
                    "$badge is a flag, not a title",
                )
            }
    }

    @Test
    fun `the line eBay writes for screen readers is not a title`() {
        // Off the phone: real Crafters at 10,000 to 25,000 euro, every one of them titled "Wird in
        // neuem Fenster oder Tab geöffnet" and then dropped for carrying none of the search.
        assertEquals(
            "VW Crafter 35 Kasten Hochdach",
            titleOf(
                """<div class="s-item__title"><span class="clipped">Wird in neuem Fenster oder Tab geöffnet</span>""" +
                    """<span role="heading">VW Crafter 35 Kasten Hochdach</span></div>""",
            ),
        )
        assertEquals(
            "",
            titleOf("""<div class="s-item__title"><span class="clipped">Opens in a new window or tab</span></div>"""),
            "an element holding nothing but that line holds no title",
        )
    }

    @Test
    fun `a title with no flag is left alone`() {
        assertEquals(
            "Lexar NM790 2TB M.2 SSD",
            titleOf("""<div class="s-item__title"><span>Lexar NM790 2TB M.2 SSD</span></div>"""),
        )
    }
}
