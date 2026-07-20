package io.github.tieo.arbay.crawler

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
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

    private val genericScriptPath: String by lazy {
        val resource = StealthBrowserClient::class.java.getResourceAsStream("/stealth_fetch.py")
            ?: error("stealth_fetch.py not found in classpath")
        val tmp = File.createTempFile("stealth_fetch", ".py")
        tmp.writeBytes(resource.readBytes())
        tmp.deleteOnExit()
        tmp.absolutePath
    }

    /** Load [url] in real Chrome and return its HTML once [waitMarker] (a literal substring, e.g. a
     *  data-qa attribute) appears in the rendered DOM, so a client-rendered grid is present rather
     *  than the empty post-challenge shell. For Cloudflare/Datadome pages whose content is painted
     *  after the challenge clears. */
    fun fetchRendered(url: String, waitMarker: String, waitSeconds: Int = 30, minMatches: Int = 1): String {
        val args = listOf("xvfb-run", "-a", "python3", genericScriptPath, url, waitMarker, waitSeconds.toString(), minMatches.toString())
        val process = ProcessBuilder(args).redirectErrorStream(false).start()

        var stderr = ""
        val stderrThread = Thread { stderr = process.errorStream.bufferedReader().readText() }
        stderrThread.isDaemon = true
        stderrThread.start()

        val outputFuture = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
        val output = try {
            outputFuture.get((waitSeconds + 45).toLong(), TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            process.destroyForcibly()
            throw CrawlerBlockedException("stealth browser timeout for $url", ErrorType.TIMEOUT)
        }
        stderrThread.join(3_000)
        process.waitFor(5, TimeUnit.SECONDS)

        val exitCode = process.exitValue()
        if (exitCode != 0) {
            log.warn("stealth render exit {} for {}: {}", exitCode, url, stderr.take(200))
            val type = if (exitCode == 2) ErrorType.CAPTCHA else ErrorType.UNKNOWN
            throw CrawlerBlockedException("stealth render $url: exit $exitCode", type)
        }
        return output.toString(Charsets.UTF_8)
    }

    /** Load [url] in real Chrome, solve the Akamai challenge once, then page through up to
     *  [maxPages] in the same session, invoking [onPage] with each page's HTML the moment the
     *  sidecar flushes it — so a caller can parse and stream results live instead of waiting for the
     *  whole multi-page crawl. Throws [CrawlerBlockedException] if page 1 is blocked (exit 2 →
     *  CAPTCHA) or the process errors/times out; pages already delivered to [onPage] stand. */
    suspend fun fetchStreaming(url: String, maxPages: Int = 1, waitSeconds: Int = 30, onPage: suspend (html: String) -> Unit) {
        val args = listOf("xvfb-run", "-a", "python3", scriptPath, url, maxPages.toString(), waitSeconds.toString())
        val process = ProcessBuilder(args).redirectErrorStream(false).start()

        var stderr = ""
        val stderrThread = Thread { stderr = process.errorStream.bufferedReader().readText() }
        stderrThread.isDaemon = true
        stderrThread.start()

        // A reader thread splits the child's stdout on the page sentinel and feeds whole pages to a
        // channel; the coroutine consumes them and calls onPage as each arrives.
        val channel = Channel<String>(Channel.UNLIMITED)
        val readerThread = Thread {
            try {
                val reader = process.inputStream.bufferedReader()
                val buf = StringBuilder()
                val chunk = CharArray(8192)
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    buf.append(chunk, 0, n)
                    var idx = buf.indexOf(PAGE_BREAK)
                    while (idx >= 0) {
                        val page = buf.substring(0, idx)
                        if (page.isNotBlank()) channel.trySend(page)
                        buf.delete(0, idx + PAGE_BREAK.length)
                        idx = buf.indexOf(PAGE_BREAK)
                    }
                }
                val tail = buf.toString()
                if (tail.isNotBlank()) channel.trySend(tail)
            } catch (_: Exception) {
            } finally {
                channel.close()
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        val timeoutMs = (waitSeconds + maxPages * 20 + 60) * 1000L
        try {
            withTimeout(timeoutMs) {
                for (page in channel) onPage(page)
            }
        } catch (_: TimeoutCancellationException) {
            process.destroyForcibly()
            throw CrawlerBlockedException("stealth browser timeout for $url", ErrorType.TIMEOUT)
        }

        stderrThread.join(3_000)
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()

        val exitCode = runCatching { process.exitValue() }.getOrElse { -1 }
        if (exitCode != 0) {
            log.warn("stealth browser exit {} for {}: {}", exitCode, url, stderr.take(200))
            val type = if (exitCode == 2) ErrorType.CAPTCHA else ErrorType.UNKNOWN
            throw CrawlerBlockedException("stealth browser $url: exit $exitCode", type)
        }
    }

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
