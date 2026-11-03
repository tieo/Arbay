package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.BlockCooldown
import io.github.tieo.arbay.crawler.CrawlerBlockedException
import io.github.tieo.arbay.crawler.EbayDeCrawler
import io.github.tieo.arbay.crawler.ErrorType
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Neuland not answering (as while it restarts for a deploy) is no refusal from eBay: on 2026-11-03
 * every eBay site went on a twenty-minute block cooldown each time Neuland restarted.
 */
class EbayNeulandSilentTest {
    @Test
    fun `a search Neuland gave no answer to is a network error and starts no cooldown`() = runBlocking {
        BlockCooldown.clear()
        // No Neuland is set up in tests, so its browser answers nothing; eBay refuses the plain client.
        val crawler = EbayDeCrawler(HttpClient(MockEngine { respond("", HttpStatusCode.Forbidden) }))
        val e = assertFailsWith<CrawlerBlockedException> { crawler.search(SearchQuery(text = "2TB NVMe", category = io.github.tieo.arbay.model.MarketGroup.GENERAL)) }
        assertEquals(ErrorType.NETWORK_ERROR, e.errorType, e.message)
        assertFalse(BlockCooldown.isBlock(e.errorType))
        assertFalse(BlockCooldown.isCoolingDown(PlatformId.EBAY_DE))
    }
}
