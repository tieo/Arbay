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
    fun `a title with no flag is left alone`() {
        assertEquals(
            "Lexar NM790 2TB M.2 SSD",
            titleOf("""<div class="s-item__title"><span>Lexar NM790 2TB M.2 SSD</span></div>"""),
        )
    }
}
