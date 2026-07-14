package io.github.tieo.arbay.crawler

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * Fires an ntfy push when a platform's block rate crosses a threshold, so a marketplace
 * starting to flag our IP is noticed before it hardens into a ban. Target is the full ntfy
 * topic URL in ARBAY_NTFY_URL (the topic is a secret, so it stays in the environment); with
 * it unset the alerter is a no-op and the server just tracks counts.
 */
object BlockAlerter {
    private val log = LoggerFactory.getLogger(BlockAlerter::class.java)
    private val ntfyUrl: String? = System.getenv("ARBAY_NTFY_URL")?.takeIf { it.isNotBlank() }
    private val http = HttpClient.newHttpClient()

    private const val MIN_REQUESTS = 8L        // ignore tiny samples
    private const val BLOCK_RATE_THRESHOLD = 0.5
    private const val COOLDOWN_MS = 30 * 60 * 1000L

    private val lastAlertMs = ConcurrentHashMap<String, Long>()

    fun consider(platform: String, total: Long, blocks: Long) {
        if (ntfyUrl == null || total < MIN_REQUESTS) return
        if (blocks.toDouble() / total < BLOCK_RATE_THRESHOLD) return
        val now = System.currentTimeMillis()
        val last = lastAlertMs[platform] ?: 0L
        if (now - last < COOLDOWN_MS) return
        lastAlertMs[platform] = now
        val pct = blocks * 100 / total
        fire(platform, "$platform block rate $pct% ($blocks/$total). IP may be getting flagged.")
    }

    private fun fire(platform: String, message: String) {
        Thread {
            try {
                val req = HttpRequest.newBuilder(URI.create(ntfyUrl))
                    .header("Title", "Arbay crawler: $platform")
                    .header("Priority", "high")
                    .header("Tags", "warning")
                    .POST(HttpRequest.BodyPublishers.ofString(message))
                    .build()
                http.send(req, HttpResponse.BodyHandlers.discarding())
            } catch (e: Exception) {
                log.warn("ntfy alert failed: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }
}
