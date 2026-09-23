package io.github.tieo.arbay.crawler

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Calls rnet_fetch.py via subprocess to fetch a URL with a current Chrome TLS/HTTP2
 * fingerprint (rnet/wreq), including the post-quantum X25519MLKEM768 key share that
 * Akamai and Cloudflare check for as of 2026. Passes TLS-gated sites that curl_cffi's
 * older profiles no longer clear (Geizhals, willhaben) without a browser.
 *
 * Requires Python 3 + rnet: pip install rnet
 */
object RnetClient {
    private val log = LoggerFactory.getLogger(RnetClient::class.java)

    private val scriptPath: String by lazy {
        val resource = RnetClient::class.java.getResourceAsStream("/rnet_fetch.py")
            ?: error("rnet_fetch.py not found in classpath — cannot use RnetClient")
        val tmp = File.createTempFile("rnet_fetch", ".py")
        tmp.writeBytes(resource.readBytes())
        tmp.deleteOnExit()
        log.info("Extracted rnet_fetch.py to {}", tmp.absolutePath)
        tmp.absolutePath
    }

    /**
     * Fetch [url] with a current Chrome TLS fingerprint.
     * @param primeUrl visited first to establish session cookies; defaults to the target origin.
     * @return raw HTML body
     */
    suspend fun fetch(url: String, primeUrl: String? = null): String {
        val args = mutableListOf("python3", scriptPath, "fetch", url)
        if (!primeUrl.isNullOrBlank()) args.add(primeUrl)
        val outcome = try {
            runProcess(args, timeoutMs = 70_000)
        } catch (_: ProcessTimedOut) {
            throw CrawlerBlockedException("rnet output timeout for $url", ErrorType.TIMEOUT)
        }
        val stderr = outcome.stderr

        val exitCode = outcome.exitCode
        if (exitCode != 0) {
            // Exit codes from rnet_fetch.py: 2=403, 3=429, 4=503, 5=other non-200, 1=script error
            log.debug("rnet exited {} for {}: {}", exitCode, url, stderr.take(200))
            val errorType = when (exitCode) {
                2 -> ErrorType.BLOCKED_403
                3 -> ErrorType.RATE_LIMITED_429
                4 -> ErrorType.SERVICE_UNAVAILABLE_503
                else -> ErrorType.UNKNOWN
            }
            val httpStatus = Regex("HTTP (\\d{3})").find(stderr)?.groupValues?.get(1)
            throw CrawlerBlockedException("rnet $url: ${httpStatus?.let { "HTTP $it" } ?: "exit $exitCode"}", errorType)
        }
        return outcome.stdout.toString(Charsets.UTF_8)
    }
}
