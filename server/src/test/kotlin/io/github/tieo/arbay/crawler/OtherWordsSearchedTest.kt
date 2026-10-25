package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.MarketGroup
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock

class OtherWordsSearchedTest {
    /** A market that answers each phrase with its own listings, the way Kleinanzeigen does. */
    private class Market(private val answers: Map<String, List<String>>) : Crawler {
        override val platformId = PlatformId.KLEINANZEIGEN
        val asked = mutableListOf<String>()
        override suspend fun search(query: SearchQuery): List<Listing> {
            asked += query.text
            return answers[query.text].orEmpty().map { title ->
                Listing(
                    id = "KLEINANZEIGEN:${title.hashCode()}", platformId = platformId, externalId = title.hashCode().toString(),
                    url = "https://www.kleinanzeigen.de/s-anzeige/x/${title.hashCode()}", title = title,
                    price = Money(79000, Currency.EUR), scrapedAt = Clock.System.now(),
                )
            }
        }
    }

    @Test
    fun `a search's other words are asked of the market too`() = runBlocking {
        val market = Market(mapOf(
            "lägler hummel" to listOf("Lägler Hummel Parkettschleifmaschine"),
            "lägler elf" to listOf("Parkettschleifmaschine LÄGLER ELF 1"),
        ))
        val query = SearchQuery(text = "lägler hummel", aliases = listOf("lägler elf"), category = MarketGroup.GENERAL)
        val found = market.searchAllSpellings(query) { 0.0 }
        assertEquals(listOf("lägler hummel", "lägler elf"), market.asked)
        assertEquals(setOf("Lägler Hummel Parkettschleifmaschine", "Parkettschleifmaschine LÄGLER ELF 1"), found.map { it.title }.toSet())
    }
}
