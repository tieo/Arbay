package io.github.tieo.arbay.crawler

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
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
     *  mobilede_fetch.py, stealth_fetch.py and kleinanzeigen_chat.py all import captcha_gate.py to
     *  show their browser over noVNC. */
    internal val scriptDir: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "arbay-stealth").apply { mkdirs() }
        for (name in listOf("mobilede_fetch.py", "stealth_fetch.py", "captcha_gate.py", "kleinanzeigen_chat.py")) {
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
    suspend fun fetchRendered(url: String, waitMarker: String, waitSeconds: Int = 30, minMatches: Int = 1): String {
        val args = listOf("xvfb-run", "-a", "python3", genericScriptPath, url, waitMarker, waitSeconds.toString(), minMatches.toString())
        val outcome = try {
            runProcess(args, timeoutMs = (waitSeconds + 45) * 1000L)
        } catch (_: ProcessTimedOut) {
            throw CrawlerBlockedException("stealth browser timeout for $url", ErrorType.TIMEOUT)
        }
        if (outcome.exitCode != 0) {
            log.warn("stealth render exit {} for {}: {}", outcome.exitCode, url, outcome.stderr.take(200))
            val type = if (outcome.exitCode == 2) ErrorType.CAPTCHA else ErrorType.UNKNOWN
            throw CrawlerBlockedException("stealth render $url: exit ${outcome.exitCode}", type)
        }
        return outcome.stdout.toString(Charsets.UTF_8)
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

        // The sidecar writes control lines (ARBAY_CTRL:*) and diagnostics to stderr, but xvfb-run
        // runs it with `2>&1`, so on the server they arrive inside stdout. Both streams are read for
        // control lines, and the controls close once both have ended.
        val streamsOpen = java.util.concurrent.atomic.AtomicInteger(2)
        fun streamEnded() { if (streamsOpen.decrementAndGet() == 0) controls.close() }
        val stderrBuf = StringBuilder()
        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    if (line.startsWith(CTRL_PREFIX)) controls.trySend(line.removePrefix(CTRL_PREFIX).trim())
                    else stderrBuf.appendLine(line)
                }
            } catch (_: Exception) {
            } finally {
                streamEnded()
            }
        }
        stderrThread.isDaemon = true
        stderrThread.start()

        // stdout is split on the page sentinel into whole pages, after any control line in it is taken out.
        val readerThread = Thread {
            try {
                val reader = process.inputStream.bufferedReader()
                val buf = StringBuilder()
                val chunk = CharArray(8192)
                while (true) {
                    val n = reader.read(chunk)
                    if (n < 0) break
                    buf.append(chunk, 0, n)
                    takeControlLines(buf).forEach { controls.trySend(it) }
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
                streamEnded()
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
        // Whether the sidecar handed its browser to a person: a run that then overruns ended on a
        // challenge nobody solved, which is a captcha and not a slow page.
        val askedForHuman = java.util.concurrent.atomic.AtomicBoolean(false)
        // The reference of an outright refusal page, which has nothing on it for a person to solve.
        val denied = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val watchdog = Thread {
            while (process.isAlive) {
                if (System.currentTimeMillis() > deadline.get()) { timedOut.set(true); killTree(process); break }
                Thread.sleep(1_000)
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        try {
            coroutineScope {
                val ctrlJob = launch {
                    for (msg in controls) {
                        if (msg.startsWith("DENIED")) denied.set(msg.removePrefix("DENIED").trim())
                        if (msg == "CAPTCHA_INTERACTIVE") {
                            askedForHuman.set(true)
                            deadline.set(System.currentTimeMillis() + INTERACTIVE_SOLVE_MS)
                        }
                        onControl(msg)
                    }
                }
                for (page in pages) onPage(page)
                ctrlJob.join()
            }
        } catch (e: Throwable) {
            // The search that wanted these pages is gone or failed; the browser must go with it
            // rather than page on until the watchdog's deadline.
            killTree(process)
            throw e
        }

        if (timedOut.get()) {
            stderrThread.join(3_000)
            val said = stderrBuf.toString().trim().takeLast(300).ifEmpty { "nothing" }
            if (askedForHuman.get()) {
                throw CrawlerBlockedException("challenge for $url was not solved in time; the browser said: $said", ErrorType.CAPTCHA)
            }
            throw CrawlerBlockedException("stealth browser timeout for $url; the browser said: $said", ErrorType.TIMEOUT)
        }

        stderrThread.join(3_000)
        if (!process.waitFor(10, TimeUnit.SECONDS)) killTree(process)

        val stderr = stderrBuf.toString()
        val exitCode = runCatching { process.exitValue() }.getOrElse { -1 }
        denied.get()?.let { reference ->
            throw CrawlerBlockedException("access denied for $url (reference $reference)", ErrorType.BLOCKED_403)
        }
        if (exitCode != 0) {
            log.warn("stealth browser exit {} for {}: {}", exitCode, url, stderr.take(200))
            val type = if (exitCode == 2) ErrorType.CAPTCHA else ErrorType.UNKNOWN
            throw CrawlerBlockedException("stealth browser $url: exit $exitCode", type)
        }
    }

    /**
     * Removes every complete control line (one that starts a line and has ended) from [buf] and
     * returns their messages in order. A control line still being written stays for the next read.
     */
    internal fun takeControlLines(buf: StringBuilder): List<String> {
        val found = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = buf.indexOf(CTRL_PREFIX, from)
            if (at < 0) break
            val end = buf.indexOf("\n", at)
            if (end < 0) break
            if (at == 0 || buf[at - 1] == '\n') {
                found += buf.substring(at + CTRL_PREFIX.length, end).trim()
                buf.delete(at, end + 1)
                from = at
            } else {
                from = end + 1
            }
        }
        return found
    }
}
