package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.repeatedTitleReport
import io.github.tieo.arbay.model.*
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The shape of a parser reading the wrong node.
 *
 * Two of them ran for months — eBay's "New Listing" flag and its "opens in a new window or tab"
 * line, each read as the title of every listing carrying it — and neither showed up anywhere,
 * because the listings then failed the search one at a time and simply went missing.
 */
class RepeatedTitleTest {

    private fun listing(title: String, id: Int) = Listing(
        id = "test:$id", platformId = PlatformId.EBAY_DE, externalId = "$id",
        url = "https://example.com/$id", title = title,
        price = Money(1000L * id, Currency.EUR), scrapedAt = Clock.System.now(),
    )

    @Test
    fun `one title on every listing is a parser reading the wrong node`() {
        val answer = (1..8).map { listing("Wird in neuem Fenster oder Tab geöffnet", it) }
        assertNotNull(repeatedTitleReport(answer))
    }

    @Test
    fun `a market repeating itself a little is a market with stock`() {
        // Three identical titles out of nine is a seller listing the same van three times.
        val answer = (1..3).map { listing("Volkswagen Crafter", it) } +
            (4..9).map { listing("Volkswagen Crafter 35 L3H2 Nr. $it", it) }
        assertNull(repeatedTitleReport(answer))
    }

    @Test
    fun `an answer too small to judge is left alone`() {
        assertNull(repeatedTitleReport((1..4).map { listing("Neues Angebot", it) }))
    }
}
