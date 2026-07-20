package io.github.tieo.arbay.crawler

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

    /** Sentinel between paginated pages in the sidecar's stdout. */
    const val PAGE_BREAK = "\n<!--ARBAY_PAGE_BREAK-->\n"

    /** Prefix of the sidecars' stderr control lines (captcha interactive/solved/timeout). */
    const val CTRL_PREFIX = "ARBAY_CTRL:"

    /** All sidecar scripts extracted together into one directory so they can import each other —
     *  mobilede_fetch.py and stealth_fetch.py both import captcha_gate.py for the interactive
     *  captcha solve. */
    private val scriptDir: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "arbay-stealth").apply { mkdirs() }
        for (name in listOf("mobilede_fetch.py", "stealth_fetch.py", "captcha_gate.py")) {
            val res = StealthBrowserClient::class.java.getResourceAsStream("/$name")
                ?: error("$name not found in classpath")
            File(dir, name).outputStream().use { res.copyTo(it) }
        }
        dir
    }
    private val scriptPath: String get() = File(scriptDir, "mobilede_fetch.py").absolutePath
    private val genericScriptPath: String get() = File(scriptDir, "stealth_fetch.py").absolutePath

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

    /** How long a human is given to solve an interactive captcha once the sidecar exposes the live
     *  browser over noVNC. The crawl's timeout is extended by this once solving begins. */
    private const val INTERACTIVE_SOLVE_MS = 200_000L

    /** Load [url] in real Chrome, solve the Akamai challenge once, then page through up to
     *  [maxPages] in the same session, invoking [onPage] with each page's HTML the moment the
     *  sidecar flushes it — so a caller can parse and stream results live instead of waiting for the
     *  whole multi-page crawl. [onControl] receives the sidecar's control messages (e.g.
     *  "CAPTCHA_INTERACTIVE" when it exposes the live browser for a human solve). Throws
     *  [CrawlerBlockedException] if page 1 is blocked (exit 2 → CAPTCHA) or the process errors/times
     *  out; pages already delivered to [onPage] stand. */
    suspend fun fetchStreaming(
        url: String,
        maxPages: Int = 1,
        waitSeconds: Int = 30,
        onControl: suspend (msg: String) -> Unit = {},
        onPage: suspend (html: String) -> Unit,
    ) {
        val args = listOf("xvfb-run", "-a", "python3", scriptPath, url, maxPages.toString(), waitSeconds.toString())
        val process = ProcessBuilder(args).redirectErrorStream(false).start()

        val pages = Channel<String>(Channel.UNLIMITED)
        val controls = Channel<String>(Channel.UNLIMITED)

        // stderr carries control lines (ARBAY_CTRL:*) that must be reacted to live, plus diagnostics.
        val stderrBuf = StringBuilder()
        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (line.startsWith(CTRL_PREFIX)) controls.trySend(line.removePrefix(CTRL_PREFIX).trim())
                    else stderrBuf.appendLine(line)
                }
            } catch (_: Exception) {
            } finally {
                controls.close()
            }
        }
        stderrThread.isDaemon = true
        stderrThread.start()

        // stdout is split on the page sentinel into whole pages.
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
                        if (page.isNotBlank()) pages.trySend(page)
                        buf.delete(0, idx + PAGE_BREAK.length)
                        idx = buf.indexOf(PAGE_BREAK)
                    }
                }
                val tail = buf.toString()
                if (tail.isNotBlank()) pages.trySend(tail)
            } catch (_: Exception) {
            } finally {
                pages.close()
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        // A watchdog kills the process if it overruns the deadline; a human solve pushes the deadline
        // out so the wait for the user does not trip the timeout. The channels close when the process
        // dies, which ends the consumption loops below.
        val deadline = java.util.concurrent.atomic.AtomicLong(
            System.currentTimeMillis() + (waitSeconds + maxPages * 20 + 60) * 1000L)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            while (process.isAlive) {
                if (System.currentTimeMillis() > deadline.get()) { timedOut.set(true); process.destroyForcibly(); break }
                Thread.sleep(1_000)
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        coroutineScope {
            val ctrlJob = launch {
                for (msg in controls) {
                    if (msg == "CAPTCHA_INTERACTIVE") deadline.set(System.currentTimeMillis() + INTERACTIVE_SOLVE_MS)
                    onControl(msg)
                }
            }
            for (page in pages) onPage(page)
            ctrlJob.join()
        }

        if (timedOut.get()) throw CrawlerBlockedException("stealth browser timeout for $url", ErrorType.TIMEOUT)

        stderrThread.join(3_000)
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()

        val stderr = stderrBuf.toString()
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
