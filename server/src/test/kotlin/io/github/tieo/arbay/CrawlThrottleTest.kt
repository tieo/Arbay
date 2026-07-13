package io.github.tieo.arbay

import io.github.tieo.arbay.crawler.CrawlThrottle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Uses distinct caller keys per case to isolate the shared singleton state. */
class CrawlThrottleTest {

    @Test
    fun firstRequestAdmitted() {
        val r = CrawlThrottle.tryAcquire("user:first", now = 1_000)
        assertIs<CrawlThrottle.Result.Permit>(r)
        r.release()
    }

    @Test
    fun rejectsWithinMinInterval() {
        val k = "user:interval"
        (CrawlThrottle.tryAcquire(k, now = 10_000) as CrawlThrottle.Result.Permit).release()
        val r = CrawlThrottle.tryAcquire(k, now = 10_100) // 100ms < 800ms
        val rejected = assertIs<CrawlThrottle.Result.Rejected>(r)
        assertTrue(rejected.retryAfterSeconds >= 1)
    }

    @Test
    fun enforcesPerMinuteWindow() {
        val k = "user:window"
        val cfg = CrawlThrottle.config
        var t = 100_000L
        repeat(cfg.perCallerPerMinute) {
            val r = CrawlThrottle.tryAcquire(k, now = t)
            assertIs<CrawlThrottle.Result.Permit>(r).release()
            t += cfg.minIntervalMs
        }
        // One more inside the same 60s window is rejected.
        val r = CrawlThrottle.tryAcquire(k, now = t)
        assertIs<CrawlThrottle.Result.Rejected>(r)
    }

    @Test
    fun globalConcurrencyCap() {
        val cfg = CrawlThrottle.config
        val held = (0 until cfg.maxConcurrent).map {
            assertIs<CrawlThrottle.Result.Permit>(CrawlThrottle.tryAcquire("user:conc-$it", now = 200_000))
        }
        // A fresh caller cannot get a slot while the cap is saturated.
        val r = CrawlThrottle.tryAcquire("user:conc-overflow", now = 200_000)
        val rejected = assertIs<CrawlThrottle.Result.Rejected>(r)
        assertEquals(2, rejected.retryAfterSeconds)
        held.forEach { it.release() }
        // After releasing, a new caller is admitted again.
        assertIs<CrawlThrottle.Result.Permit>(CrawlThrottle.tryAcquire("user:conc-after", now = 200_000)).release()
    }
}
