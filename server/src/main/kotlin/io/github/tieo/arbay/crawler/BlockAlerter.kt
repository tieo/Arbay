package io.github.tieo.arbay.crawler

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Records when a platform's block rate crosses a threshold, so a marketplace starting to flag our
 * IP is visible in the server log before it hardens into a ban.
 *
 * This is a server health signal, addressed to whoever reads the log. Notifications reach the
 * phone and are about a thing to fetch or buy; a crawler being throttled is neither.
 */
object BlockAlerter {
    private val log = LoggerFactory.getLogger(BlockAlerter::class.java)

    private const val MIN_REQUESTS = 8L        // ignore tiny samples
    private const val BLOCK_RATE_THRESHOLD = 0.5
    private const val COOLDOWN_MS = 30 * 60 * 1000L

    private val lastLoggedMs = ConcurrentHashMap<String, Long>()

    fun consider(platform: String, total: Long, blocks: Long) {
        if (total < MIN_REQUESTS) return
        if (blocks.toDouble() / total < BLOCK_RATE_THRESHOLD) return
        val now = System.currentTimeMillis()
        val last = lastLoggedMs[platform] ?: 0L
        if (now - last < COOLDOWN_MS) return
        lastLoggedMs[platform] = now
        val pct = blocks * 100 / total
        log.warn("{} block rate {}% ({}/{}). IP may be getting flagged.", platform, pct, blocks, total)
    }
}
