package io.github.tieo.arbay.crawler

import org.slf4j.LoggerFactory
import java.io.File

/**
 * Calls cffi_fetch.py via subprocess to make HTTP requests with a real Chrome 131 TLS/HTTP2 fingerprint.
 * This bypasses Cloudflare Bot Management (Back Market) and Akamai (Idealo) which block headless Chromium.
 *
 * Requires Python 3 + curl_cffi installed: pip3 install curl_cffi
 */
object CurlCffiClient {
    private val log = LoggerFactory.getLogger(CurlCffiClient::class.java)

    private val scriptPath: String by lazy {
        val resource = CurlCffiClient::class.java.getResourceAsStream("/cffi_fetch.py")
            ?: error("cffi_fetch.py not found in classpath — cannot use CurlCffiClient")
        val tmp = File.createTempFile("cffi_fetch", ".py")
        tmp.writeBytes(resource.readBytes())
        tmp.deleteOnExit()
        log.info("Extracted cffi_fetch.py to {}", tmp.absolutePath)
        tmp.absolutePath
    }

    /**
     * Fetch [url] using Chrome TLS impersonation.
     * @param primeUrl If provided, visits this URL first to establish session cookies (e.g. homepage priming).
     * @return raw HTML body
     */
    suspend fun fetch(url: String, primeUrl: String? = null): String {
        val args = mutableListOf("python3", scriptPath, "fetch", url)
        if (!primeUrl.isNullOrBlank()) args.add(primeUrl)
        return run(args, url)
    }

    /**
     * Search Idealo via /suggest API + concurrent product page title scraping.
     * @return JSON string: array of {title, url, id, price_text}
     */
    suspend fun idealoSearch(query: String): String {
        return run(listOf("python3", scriptPath, "idealo", query), "idealo:$query")
    }

    /**
     * Search Refurbed via /search-autosuggest API + concurrent product page price extraction.
     * @return JSON string: array of {title, url, id, price_cents, image_url}
     */
    suspend fun refurbedSearch(query: String): String {
        return run(listOf("python3", scriptPath, "refurbed", query), "refurbed:$query")
    }

    private suspend fun run(args: List<String>, desc: String): String {
        log.debug("CurlCffi launching: {}", desc)
        val outcome = try {
            runProcess(args, timeoutMs = 70_000)
        } catch (_: ProcessTimedOut) {
            throw CrawlerBlockedException("curl_cffi output timeout for $desc", ErrorType.TIMEOUT)
        }
        val stderr = outcome.stderr

        val exitCode = outcome.exitCode
        if (exitCode != 0) {
            // Exit codes from cffi_fetch.py: 2=403, 3=429, 4=503, 5=other non-200, 1=script error
            // stderr contains "HTTP <status>" for non-200 responses
            log.warn("CurlCffi exited {} for {}: {}", exitCode, desc, stderr.take(300))
            val httpStatus = Regex("HTTP (\\d{3})").find(stderr)?.groupValues?.get(1)?.toIntOrNull()
            val errorType = when (exitCode) {
                2 -> ErrorType.BLOCKED_403
                3 -> ErrorType.RATE_LIMITED_429
                4 -> ErrorType.SERVICE_UNAVAILABLE_503
                else -> ErrorType.UNKNOWN
            }
            val statusStr = if (httpStatus != null) "HTTP $httpStatus" else "exit $exitCode"
            throw CrawlerBlockedException("$desc: $statusStr", errorType)
        }

        if (stderr.isNotBlank()) log.debug("CurlCffi stderr for {}: {}", desc, stderr.take(200))
        return outcome.stdout.toString(Charsets.UTF_8)
    }
}
