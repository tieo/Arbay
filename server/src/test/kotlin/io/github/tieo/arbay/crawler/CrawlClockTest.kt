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
    fun `the time spent waiting on other searches does not count`() = runBlocking {
        val answer = withCrawlLimitOrNull(300) {
            val clock = coroutineContext[CrawlClock]!!
            delay(100)
            clock.waitingOnOthers { delay(600) }
            delay(100)
            "waited"
        }
        assertEquals("waited", answer)
    }

    @Test
    fun `the clock runs again once the wait is over`() = runBlocking {
        assertNull(withCrawlLimitOrNull(300) {
            val clock = coroutineContext[CrawlClock]!!
            clock.waitingOnOthers { delay(100) }
            delay(1_000)
            "too late"
        })
    }

    @Test
    fun `a crawl that keeps answering pages is not given up on`() = runBlocking {
        val answer = withCrawlLimitOrNull(300) {
            val clock = coroutineContext[CrawlClock]!!
            repeat(6) { delay(150); clock.answered() }
            "every page"
        }
        assertEquals("every page", answer)
    }

    @Test
    fun `a crawl that stops answering is given up on the limit after its last page`() = runBlocking {
        assertNull(withCrawlLimitOrNull(300) {
            val clock = coroutineContext[CrawlClock]!!
            repeat(3) { delay(150); clock.answered() }
            delay(1_000)
            "too late"
        })
    }
}
