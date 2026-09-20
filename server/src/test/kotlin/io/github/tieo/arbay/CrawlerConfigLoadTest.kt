package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CrawlerConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class CrawlerConfigLoadTest {
    @Test
    fun `a setting this build does not know keeps the rest of the file`() {
        val config = CrawlerConfig.parse(
            """{"maxResultsPerPlatform":90,"maxPages":8,"sortByPrice":true,"ebayDeCarCategory":"9800"}""",
        )
        assertEquals(90, config.maxResultsPerPlatform)
        assertEquals("9800", config.ebayDeCarCategory)
    }

    @Test
    fun `an unreadable file falls back to the defaults instead of throwing`() {
        assertEquals(CrawlerConfig(), CrawlerConfig.parse("not json at all"))
    }
}
