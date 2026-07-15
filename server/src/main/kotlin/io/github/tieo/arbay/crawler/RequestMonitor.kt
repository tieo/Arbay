package io.github.tieo.arbay.crawler

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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

    // Hard cutoff: no more than this many outbound requests to one platform per rolling window.
    // A normal search is well under this; the cap only trips a runaway (retry/pagination/detail
    // fetch stacking) before it can hammer a site into flagging our IP.
    private const val WINDOW_MS = 60_000L
    private const val MAX_PER_WINDOW = 45

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

    /** True when this platform has hit the per-window request ceiling — callers must skip it. */
    fun overBudget(platform: String): Boolean {
        val c = byPlatform[platform] ?: return false
        val now = Clock.System.now().toEpochMilliseconds()
        return synchronized(c.recentMs) {
            while (c.recentMs.isNotEmpty() && now - c.recentMs.first() > WINDOW_MS) c.recentMs.removeFirst()
            c.recentMs.size >= MAX_PER_WINDOW
        }
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
