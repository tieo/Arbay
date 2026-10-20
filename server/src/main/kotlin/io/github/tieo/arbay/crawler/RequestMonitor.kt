package io.github.tieo.arbay.crawler

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Outbound-request telemetry per marketplace, so a rising block rate or a fallback chain
 * that keeps escalating to the browser (both signs of an IP getting flagged) is visible
 * instead of silent. Counts every fetch attempt, which fetch tier ended up serving it, and
 * how often a platform blocked us. Exposed at GET /api/crawler/request-stats.
 */
object RequestMonitor {
    @Serializable
    data class PlatformRequestStats(
        val platform: String,
        val totalRequests: Long = 0,
        val blocks: Long = 0,
        val tierHttp: Long = 0,
        val tierRnet: Long = 0,
        val tierCurlCffi: Long = 0,
        val tierBrowser: Long = 0,
        val lastRequest: Instant? = null,
        val lastBlock: Instant? = null,
    ) {
        /** Share of requests that ended in a block, 0..1. High + rising means trouble. */
        val blockRate: Double get() = if (totalRequests == 0L) 0.0 else blocks.toDouble() / totalRequests
    }

    // No more than this many outbound requests to one platform per rolling window, so neither a
    // runaway (retry/pagination/detail fetch stacking) nor a search reading a whole price band
    // hammers a site into flagging our IP. A fetch over it waits for the window ([acquire]): a
    // Crafter watch reads 45 Kleinanzeigen pages before 23,500 € of its 30,990 € band, and a
    // ceiling that ended the read there never saw the vans priced above it.
    private const val WINDOW_MS = 60_000L
    private const val MAX_PER_WINDOW = 45

    /** How long one fetch may wait for its market's window: past a whole window, other searches
     *  are taking it as fast as it opens. */
    const val MAX_WAIT_FOR_WINDOW_MS = 90_000L

    private class Counters {
        val total = AtomicLong()
        val blocks = AtomicLong()
        val http = AtomicLong()
        val rnet = AtomicLong()
        val curlCffi = AtomicLong()
        val browser = AtomicLong()
        @Volatile var lastRequest: Instant? = null
        @Volatile var lastBlock: Instant? = null
        val recentMs = ArrayDeque<Long>() // request timestamps in the rolling window
    }

    private val byPlatform = ConcurrentHashMap<String, Counters>()

    private fun counters(platform: String) = byPlatform.getOrPut(platform) { Counters() }

    fun recordRequest(platform: String) {
        val c = counters(platform)
        c.total.incrementAndGet()
        c.lastRequest = Clock.System.now()
        val now = Clock.System.now().toEpochMilliseconds()
        synchronized(c.recentMs) {
            c.recentMs.addLast(now)
            while (c.recentMs.isNotEmpty() && now - c.recentMs.first() > WINDOW_MS) c.recentMs.removeFirst()
        }
    }

    /**
     * Takes one request from [platform]'s window, waiting for the window to open while that is
     * before [deadlineMs], and answers false when it would not be. The check and the count are one
     * step, so searches waiting on the same window cannot all wake and spend it past its ceiling.
     */
    suspend fun acquire(platform: String, deadlineMs: Long): Boolean {
        val c = counters(platform)
        fun take(): Long? = synchronized(c.recentMs) {
            val now = Clock.System.now().toEpochMilliseconds()
            while (c.recentMs.isNotEmpty() && now - c.recentMs.first() > WINDOW_MS) c.recentMs.removeFirst()
            if (c.recentMs.size < MAX_PER_WINDOW) {
                c.recentMs.addLast(now)
                c.total.incrementAndGet()
                c.lastRequest = Clock.System.now()
                null
            } else c.recentMs.first() + WINDOW_MS
        }
        var freeAt = take() ?: return true
        // The window was spent by other searches as much as by this one, so the wait does not
        // count against this market's time limit.
        val clock = kotlin.coroutines.coroutineContext[CrawlClock]
        suspend fun waitForTurn(): Boolean {
            while (true) {
                if (freeAt > deadlineMs) return false
                kotlinx.coroutines.delay((freeAt - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(50))
                freeAt = take() ?: return true
            }
        }
        return clock?.waitingOnOthers { waitForTurn() } ?: waitForTurn()
    }

    /** True when this platform has hit the per-window request ceiling — callers must skip it. */
    fun overBudget(platform: String): Boolean {
        val c = byPlatform[platform] ?: return false
        val now = Clock.System.now().toEpochMilliseconds()
        return synchronized(c.recentMs) {
            while (c.recentMs.isNotEmpty() && now - c.recentMs.first() > WINDOW_MS) c.recentMs.removeFirst()
            c.recentMs.size >= MAX_PER_WINDOW
        }
    }

    /**
     * Waits until [platform] is under its request ceiling again, as long as that is before
     * [deadlineMs]. Answers false when it would not be, without waiting.
     */
    suspend fun awaitBudget(platform: String, deadlineMs: Long): Boolean {
        if (!overBudget(platform)) return true
        // The window was spent by other searches as much as by this one, so the wait does not
        // count against this market's time limit.
        val clock = kotlin.coroutines.coroutineContext[CrawlClock] ?: return waitForWindow(platform, deadlineMs)
        return clock.waitingOnOthers { waitForWindow(platform, deadlineMs) }
    }

    private suspend fun waitForWindow(platform: String, deadlineMs: Long): Boolean {
        while (overBudget(platform)) {
            val c = byPlatform[platform] ?: return true
            val freeAt = (synchronized(c.recentMs) { c.recentMs.firstOrNull() } ?: return true) + WINDOW_MS
            if (freeAt > deadlineMs) return false
            kotlinx.coroutines.delay((freeAt - Clock.System.now().toEpochMilliseconds()).coerceAtLeast(50))
        }
        return true
    }

    fun recordTier(platform: String, tier: String) {
        val c = counters(platform)
        when (tier) {
            "HTTP" -> c.http
            "Rnet" -> c.rnet
            "CurlCffi" -> c.curlCffi
            else -> c.browser
        }.incrementAndGet()
    }

    fun recordBlock(platform: String) {
        val c = counters(platform)
        c.blocks.incrementAndGet()
        c.lastBlock = Clock.System.now()
        BlockAlerter.consider(platform, total = c.total.get(), blocks = c.blocks.get())
    }

    fun getAll(): List<PlatformRequestStats> = byPlatform.entries
        .map { (platform, c) ->
            PlatformRequestStats(
                platform = platform,
                totalRequests = c.total.get(),
                blocks = c.blocks.get(),
                tierHttp = c.http.get(),
                tierRnet = c.rnet.get(),
                tierCurlCffi = c.curlCffi.get(),
                tierBrowser = c.browser.get(),
                lastRequest = c.lastRequest,
                lastBlock = c.lastBlock,
            )
        }
        .sortedByDescending { it.totalRequests }
}
