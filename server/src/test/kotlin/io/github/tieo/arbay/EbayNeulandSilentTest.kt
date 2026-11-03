package io.github.tieo.arbay

import io.github.tieo.arbay.markets.BlockCooldown
import io.github.tieo.arbay.markets.CrawlerBlockedException
import io.github.tieo.arbay.markets.EbayDeCrawler
import io.github.tieo.arbay.markets.ErrorType
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
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
        // No Neuland is set up in tests, so it answers nothing.
        val crawler = EbayDeCrawler()
        val e = assertFailsWith<CrawlerBlockedException> { crawler.search(SearchQuery(text = "2TB NVMe", category = io.github.tieo.arbay.model.MarketGroup.GENERAL)) }
        assertEquals(ErrorType.NETWORK_ERROR, e.errorType, e.message)
        assertFalse(BlockCooldown.isBlock(e.errorType))
        assertFalse(BlockCooldown.isCoolingDown(PlatformId.EBAY_DE))
    }
}
