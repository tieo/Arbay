package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.testing.offlineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarktplaatsApiParseTest {
    private val answer = javaClass.getResource("/fixtures/marktplaats_api.json")!!.readText()

    @Test
    fun `an API answer is read under the numbers the result page links carry`() {
        val listings = MarktplaatsCrawler(offlineClient()).parseApiAnswer(answer)
        assertEquals(6, listings.size)
        assertTrue(listings.all { it.externalId.all(Char::isDigit) }, listings.joinToString { it.externalId })
        val dealer = listings.single { it.externalId == "1524104910" }
        assertEquals(14999, dealer.price.amount)
        assertTrue(dealer.url.startsWith("https://www.marktplaats.nl/v/"), dealer.url)
    }
}
