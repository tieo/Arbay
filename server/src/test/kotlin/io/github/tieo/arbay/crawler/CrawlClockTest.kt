package io.github.tieo.arbay.crawler

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrawlClockTest {

    @Test
    fun `a crawl that never answers is given up on`() = runBlocking {
        assertNull(withCrawlLimitOrNull(200) { awaitCancellation() })
    }

    @Test
    fun `a crawl that answers in time keeps its answer`() = runBlocking {
        assertEquals("page", withCrawlLimitOrNull(5_000) { delay(50); "page" })
    }

    @Test
    fun `the time a person spends solving a captcha does not count`() = runBlocking {
        val answer = withCrawlLimitOrNull(300) {
            val clock = coroutineContext[CrawlClock]!!
            delay(100)
            clock.personSolving(true)
            delay(600)
            clock.personSolving(false)
            delay(100)
            "solved"
        }
        assertEquals("solved", answer)
    }

    @Test
    fun `the clock runs again once the person is done`() = runBlocking {
        assertNull(withCrawlLimitOrNull(300) {
            val clock = coroutineContext[CrawlClock]!!
            clock.personSolving(true)
            delay(100)
            clock.personSolving(false)
            delay(1_000)
            "too late"
        })
    }
}
