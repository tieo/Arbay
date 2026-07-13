package io.github.tieo.arbay.crawler

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Fetches Akamai-protected pages by shelling out to mobilede_fetch.py, which drives real
 * Google Chrome via zendriver (undetected CDP) under Xvfb. This passes mobile.de's Akamai
 * Bot Manager where TLS impersonation and Playwright/patchright are detected: real Chrome
 * gives a clean fingerprint, zendriver hides the automation leak, and a headed window
 * (even virtual) avoids the headless signal.
 *
 * Requires google-chrome-stable, zendriver, and xvfb in the runtime image.
 */
object StealthBrowserClient {
    private val log = LoggerFactory.getLogger(StealthBrowserClient::class.java)

    private val scriptPath: String by lazy {
        val resource = StealthBrowserClient::class.java.getResourceAsStream("/mobilede_fetch.py")
            ?: error("mobilede_fetch.py not found in classpath")
        val tmp = File.createTempFile("mobilede_fetch", ".py")
        tmp.writeBytes(resource.readBytes())
        tmp.deleteOnExit()
        tmp.absolutePath
    }

    /** Sentinel between paginated pages in the sidecar's stdout. */
    const val PAGE_BREAK = "\n<!--ARBAY_PAGE_BREAK-->\n"

    /** Load [url] in real Chrome, solve the Akamai challenge once, then page through up to
     *  [maxPages] in the same session. Returns each page's HTML joined by [PAGE_BREAK]. */
    fun fetch(url: String, maxPages: Int = 1, waitSeconds: Int = 30): String {
        // xvfb-run gives Chrome a virtual display on a headless host. -a picks a free display.
        val args = listOf("xvfb-run", "-a", "python3", scriptPath, url, maxPages.toString(), waitSeconds.toString())
        val process = ProcessBuilder(args).redirectErrorStream(false).start()

        var stderr = ""
        val stderrThread = Thread { stderr = process.errorStream.bufferedReader().readText() }
        stderrThread.isDaemon = true
        stderrThread.start()

        val outputFuture = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
        val output = try {
            outputFuture.get((waitSeconds + maxPages * 20 + 60).toLong(), TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            process.destroyForcibly()
            throw CrawlerBlockedException("stealth browser timeout for $url", ErrorType.TIMEOUT)
        }

        stderrThread.join(3_000)
        process.waitFor(5, TimeUnit.SECONDS)

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            log.warn("stealth browser exit {} for {}: {}", exitCode, url, stderr.take(200))
            val type = if (exitCode == 2) ErrorType.CAPTCHA else ErrorType.UNKNOWN
            throw CrawlerBlockedException("stealth browser $url: exit $exitCode", type)
        }
        return output.toString(Charsets.UTF_8)
    }
}
