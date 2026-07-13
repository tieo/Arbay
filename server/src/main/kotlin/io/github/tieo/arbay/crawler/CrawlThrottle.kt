package io.github.tieo.arbay.crawler

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bounds how often live scrapes fire, so a single app user (or a leaked credential)
 * cannot drive enough traffic to get the server's IP banned by a target marketplace.
 *
 * Three limits, all per live-scrape request:
 *  - a per-caller sliding window (max requests per minute),
 *  - a per-caller minimum interval between requests,
 *  - a global concurrency cap across all callers.
 *
 * The caller key is the Authelia-forwarded user when present, else the client IP, so an
 * authenticated account is limited as one identity regardless of source address.
 */
object CrawlThrottle {

    data class Config(
        val perCallerPerMinute: Int = 15,
        val minIntervalMs: Long = 800,
        val maxConcurrent: Int = 4,
    )

    @Volatile
    var config: Config = Config()

    private val windowMs = 60_000L
    private val callerHits = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val callerLast = ConcurrentHashMap<String, Long>()
    private val active = AtomicInteger(0)

    sealed class Result {
        /** A held concurrency slot. [release] once the scrape finishes (in a finally). */
        class Permit(private val released: AtomicInteger) : Result() {
            private var done = false
            fun release() {
                if (!done) { done = true; released.decrementAndGet() }
            }
        }
        /** Rejected. [retryAfterSeconds] is a hint for the client's Retry-After header. */
        data class Rejected(val reason: String, val retryAfterSeconds: Long) : Result()
    }

    /** Attempts to admit one live-scrape request for [callerKey]. */
    fun tryAcquire(callerKey: String, now: Long = System.currentTimeMillis()): Result {
        val cfg = config

        // Per-caller minimum interval.
        val last = callerLast[callerKey]
        if (last != null && now - last < cfg.minIntervalMs) {
            return Result.Rejected("Slow down", 1)
        }

        // Per-caller sliding window.
        val hits = callerHits.getOrPut(callerKey) { ArrayDeque() }
        synchronized(hits) {
            while (hits.isNotEmpty() && now - hits.first() > windowMs) hits.removeFirst()
            if (hits.size >= cfg.perCallerPerMinute) {
                val retry = ((windowMs - (now - hits.first())) / 1000).coerceAtLeast(1)
                return Result.Rejected("Rate limit: ${cfg.perCallerPerMinute}/min", retry)
            }
            hits.addLast(now)
        }
        callerLast[callerKey] = now

        // Global concurrency cap.
        while (true) {
            val cur = active.get()
            if (cur >= cfg.maxConcurrent) {
                // Roll back the window entry we just added so a busy rejection doesn't
                // count against the caller's quota.
                synchronized(hits) { hits.removeLastOrNull() }
                return Result.Rejected("Server busy, try again shortly", 2)
            }
            if (active.compareAndSet(cur, cur + 1)) break
        }
        return Result.Permit(active)
    }
}
