package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.RelevanceFilter
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Clock

/** An agent noun names both a machine and the tradesman who works it, so a compound search that
 *  looks under it must not return job postings. */
class WantedAdFilterTest {

    private fun listing(title: String) = Listing(
        id = "KLEINANZEIGEN:${title.hashCode()}",
        platformId = PlatformId.KLEINANZEIGEN,
        externalId = title.hashCode().toString(),
        url = "https://example.invalid/${title.hashCode()}",
        title = title,
        price = Money(50_000, Currency.EUR),
        scrapedAt = Clock.System.now(),
    )

    private fun keptTitles(titles: List<String>, query: String = "parkettschleifmaschine") =
        RelevanceFilter.filter(titles.map(::listing), SearchQuery(text = query, category = MarketGroup.GENERAL)).map { it.title }

    @Test
    fun `drops job postings and wanted ads`() {
        val dropped = listOf(
            "Werde Teil unseres Teams: Bodenleger und Parkettschleifer (m/w/d) gesucht!",
            "Suche erfahrenen Parkettschleifer",
            "Parkettschleifer gesucht",
            "Minijob Parkettschleifer",
        )
        assertEquals(emptyList(), keptTitles(dropped))
    }

    @Test
    fun `keeps machines for sale`() {
        val kept = listOf(
            "Frank FSR 20 Parkettschleifer",
            "Haffner HBS 3 Bandschleifer Parkettschleifer",
            "Lägler Hummel Parkettschleifmaschine",
        )
        assertEquals(kept.size, keptTitles(kept).size)
    }

    @Test
    fun `a query that asks for a wanted ad still gets one`() {
        val titles = listOf("Suche Parkettschleifmaschine")
        assertTrue(keptTitles(titles, query = "suche parkettschleifmaschine").isNotEmpty())
    }
}
