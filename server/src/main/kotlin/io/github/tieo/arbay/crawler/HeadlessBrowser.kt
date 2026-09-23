package io.github.tieo.arbay.crawler

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitForSelectorState
import com.microsoft.playwright.options.WaitUntilState
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import org.slf4j.LoggerFactory

enum class BrowserEngine { CHROMIUM, FIREFOX, WEBKIT }

object HeadlessBrowser {
    private val log = LoggerFactory.getLogger(HeadlessBrowser::class.java)

    private val STEALTH_SCRIPT = """
        try { Object.defineProperty(navigator, 'webdriver', { get: () => undefined }); } catch(_) {}
        try { delete navigator.__proto__.webdriver; } catch(_) {}
        try {
            Object.defineProperty(navigator, 'plugins', { get: () => {
                const arr = [
                    { name: 'PDF Viewer', filename: 'internal-pdf-viewer', description: 'Portable Document Format', length: 1 },
                    { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: '', length: 1 },
                    { name: 'Chromium PDF Viewer', filename: 'internal-pdf-viewer', description: '', length: 1 },
                ];
                arr.item = function(i) { return this[i]; };
                arr.namedItem = function(n) { return Array.prototype.find.call(this, p => p.name === n) || null; };
                arr.refresh = function() {};
                return arr;
            }});
        } catch(_) {}
        try { Object.defineProperty(navigator, 'languages', { get: () => ['de-DE', 'de', 'en-US', 'en'] }); } catch(_) {}
        if (!window.chrome) { window.chrome = { runtime: {}, loadTimes: function() {}, csi: function() {} }; }
        try {
            const origQuery = window.navigator.permissions.query.bind(navigator.permissions);
            window.navigator.permissions.query = (p) =>
                p.name === 'notifications' ? Promise.resolve({ state: Notification.permission }) : origQuery(p);
        } catch(_) {}
        try {
            const brands = [
                { brand: 'Google Chrome', version: '136' },
                { brand: 'Not-A.Brand', version: '99' },
                { brand: 'Chromium', version: '136' },
            ];
            const fullBrands = [
                { brand: 'Google Chrome', version: '136.0.7103.48' },
                { brand: 'Not-A.Brand', version: '99.0.0.0' },
                { brand: 'Chromium', version: '136.0.7103.48' },
            ];
            const uad = {
                brands, mobile: false, platform: 'Windows',
                getHighEntropyValues: (hints) => Promise.resolve({
                    architecture: 'x86', bitness: '64', brands: fullBrands, fullVersionList: fullBrands,
                    mobile: false, model: '', platform: 'Windows', platformVersion: '10.0',
                    uaFullVersion: '136.0.7103.48', wow64: false,
                }),
                toJSON: () => ({ brands, mobile: false, platform: 'Windows' }),
            };
            Object.defineProperty(navigator, 'userAgentData', { get: () => uad });
        } catch(_) {}
    """.trimIndent()

    private val FIREFOX_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    // The virtual display the non-headless Chromium draws on. Its output is discarded rather than
    // piped: a pipe nobody reads is closed once the Process is collected, and Xvfb writes keymap
    // warnings for every client that connects, so the first Chromium to connect killed it with
    // SIGPIPE and every launch after fell back to headless, the mode bot checks catch first.
    @Volatile private var xvfb: Process? = null
    private val xvfbLock = Any()

    /** Whether display :99 is up, starting (or restarting) Xvfb when it is not. */
    private fun ensureXvfb(): Boolean = synchronized(xvfbLock) {
        if (xvfb?.isAlive == true) return true
        try {
            // Whatever held :99 before is dead, so its lock would only keep the new one from starting.
            File("/tmp/.X99-lock").delete()
            File("/tmp/.X11-unix/X99").delete()
            val process = ProcessBuilder("Xvfb", ":99", "-screen", "0", "1366x768x24", "-nolisten", "tcp")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            Thread.sleep(500)
            if (!process.isAlive) {
                log.warn("Xvfb exited at once with {}; Chromium runs headless", process.exitValue())
                return false
            }
            xvfb = process
            log.info("Started Xvfb on :99")
            true
        } catch (e: Exception) {
            log.warn("Xvfb not available: {}", e.message)
            false
        }
    }

    private val chromiumArgs = listOf(
        "--disable-blink-features=AutomationControlled",
        "--disable-dev-shm-usage", "--no-sandbox", "--disable-infobars",
        "--disable-background-timer-throttling",
        "--disable-backgrounding-occluded-windows",
        "--disable-renderer-backgrounding", "--disable-gpu",
    )

    /**
     * One Playwright with its own browsers, used only from its own thread.
     *
     * Playwright for Java is not thread-safe: everything it creates must be driven from the
     * thread that created it. Two fetches used to share one instance from two threads at once,
     * which is what "Cannot find object to call __adopt__" and "Object doesn't exist" were, and
     * each of those invalidated the shared browser under the other fetch too. Each worker now
     * owns its instance and runs every call on its one thread.
     */
    private class Worker(name: String) {
        private val thread = Executors.newSingleThreadExecutor { task -> Thread(task, name).apply { isDaemon = true } }

        /** Run [block] on this worker's thread and wait for it. */
        fun <T> onItsThread(block: Worker.() -> T): T = try {
            thread.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }

        private var playwrightInstance: Playwright? = null
        private val playwright: Playwright
            get() = playwrightInstance ?: run {
                log.info("Initializing Playwright...")
                Playwright.create().also { playwrightInstance = it }
            }

        // Browser instances — recreated if the process dies
        private var chromiumBrowser: Browser? = null
        private var firefoxBrowser: Browser? = null
        private var webkitBrowser: Browser? = null

        private fun launchChromium(): Browser {
            if (ensureXvfb()) {
                // Non-headless via Xvfb — best for PoW challenges
                val env = mutableMapOf(
                    "DISPLAY" to ":99",
                    "DBUS_SESSION_BUS_ADDRESS" to "/dev/null", // suppress D-Bus errors
                )
                log.info("Launching Chromium (non-headless via Xvfb)...")
                return try {
                    playwright.chromium().launch(
                        BrowserType.LaunchOptions()
                            .setHeadless(false)
                            .setEnv(env)
                            .setArgs(chromiumArgs),
                    )
                } catch (e: Exception) {
                    // Xvfb might be stale — fall back to headless
                    log.warn("Non-headless Chromium failed, falling back to headless: {}", e.message?.take(600))
                    playwright.chromium().launch(
                        BrowserType.LaunchOptions()
                            .setHeadless(true)
                            .setArgs(chromiumArgs),
                    )
                }
            } else {
                log.info("Launching Chromium (headless)...")
                return playwright.chromium().launch(
                    BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setArgs(chromiumArgs),
                )
            }
        }

        fun invalidateBrowser(engine: BrowserEngine) {
            when (engine) {
                BrowserEngine.CHROMIUM -> { try { chromiumBrowser?.close() } catch (_: Exception) {}; chromiumBrowser = null }
                BrowserEngine.FIREFOX -> { try { firefoxBrowser?.close() } catch (_: Exception) {}; firefoxBrowser = null }
                BrowserEngine.WEBKIT -> { try { webkitBrowser?.close() } catch (_: Exception) {}; webkitBrowser = null }
            }
        }

        fun browserFor(engine: BrowserEngine): Browser {
            fun launch(): Browser = when (engine) {
                BrowserEngine.CHROMIUM -> launchChromium().also { chromiumBrowser = it }
                BrowserEngine.FIREFOX -> {
                    log.info("Launching Firefox headless...")
                    playwright.firefox().launch(BrowserType.LaunchOptions().setHeadless(true)).also { firefoxBrowser = it }
                }
                BrowserEngine.WEBKIT -> {
                    log.info("Launching WebKit headless...")
                    playwright.webkit().launch(BrowserType.LaunchOptions().setHeadless(true)).also { webkitBrowser = it }
                }
            }

            val existing = when (engine) {
                BrowserEngine.CHROMIUM -> chromiumBrowser
                BrowserEngine.FIREFOX -> firefoxBrowser
                BrowserEngine.WEBKIT -> webkitBrowser
            }
            if (existing != null && existing.isConnected) return existing

            return try {
                launch()
            } catch (e: Exception) {
                // Playwright connection may be dead — recreate everything
                log.warn("[{}] Browser launch failed, recreating Playwright: {}", engine, e.message)
                try { playwrightInstance?.close() } catch (_: Exception) {}
                playwrightInstance = null
                chromiumBrowser = null; firefoxBrowser = null; webkitBrowser = null
                launch()
            }
        }

        fun fetch(
            url: String,
            waitSelector: String? = null,
            waitTimeoutMs: Double = 15_000.0,
            extraWaitMs: Long = 0,
            waitNetworkIdle: Boolean = false,
            primeUrl: String? = null,
            engine: BrowserEngine = BrowserEngine.CHROMIUM,
        ): FetchResult {
            val isFirefox = engine == BrowserEngine.FIREFOX
            val ua = if (isFirefox) FIREFOX_UA else USER_AGENT
            val contextOpts = Browser.NewContextOptions()
                .setUserAgent(ua)
                .setLocale("de-DE")
                .setViewportSize(1366, 768)
                .setExtraHTTPHeaders(mapOf(
                    "Accept-Language" to "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Upgrade-Insecure-Requests" to "1",
                    "Cache-Control" to "max-age=0",
                ))
            val context = try {
                browserFor(engine).newContext(contextOpts)
            } catch (e: Exception) {
                // Browser process may have died — invalidate and retry once
                log.warn("[{}] Browser context creation failed, relaunching: {}", engine, e.message)
                invalidateBrowser(engine)
                browserFor(engine).newContext(contextOpts)
            }
            return try {
                val page = context.newPage()

                // Stealth only for Chromium (Firefox/WebKit don't need it)
                if (engine == BrowserEngine.CHROMIUM) page.addInitScript(STEALTH_SCRIPT)

                // Block heavy resources
                page.route("**/*.{png,jpg,jpeg,gif,svg,webp,woff,woff2,ttf,eot}") { it.abort() }

                // Prime session
                if (primeUrl != null) {
                    log.debug("[{}] Priming via {}", engine, primeUrl)
                    try {
                        page.navigate(primeUrl, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(20_000.0))
                        if (isChallengePage(page.url())) {
                            log.debug("[{}] Priming hit challenge, waiting...", engine)
                            waitForChallenge(page)
                        } else {
                            page.waitForTimeout(1_500.0)
                        }
                    } catch (_: Exception) {}
                }

                page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000.0))

                // Wait through JS challenges
                if (isChallengePage(page.url())) {
                    log.debug("[{}] Challenge on target URL, waiting...", engine)
                    if (!waitForChallenge(page)) {
                        return FetchResult(html = "", statusCode = 403, finalUrl = page.url())
                    }
                }

                if (waitSelector != null) {
                    page.waitForSelector(waitSelector, Page.WaitForSelectorOptions().setTimeout(waitTimeoutMs).setState(WaitForSelectorState.ATTACHED))
                }
                if (waitNetworkIdle) {
                    page.waitForLoadState(LoadState.NETWORKIDLE, Page.WaitForLoadStateOptions().setTimeout(30_000.0))
                }
                if (extraWaitMs > 0) page.waitForTimeout(extraWaitMs.toDouble())

                FetchResult(html = page.content(), statusCode = 200, finalUrl = page.url())
            } catch (e: Exception) {
                log.error("[{}] Fetch failed for {}: {}", engine, url.take(80), e.message)
                if (isPlaywrightStaleError(e)) invalidateBrowser(engine)
                throw e
            } finally {
                try { context.close() } catch (_: Exception) {}
            }
        }

        fun <T> withSessionInner(
            engine: BrowserEngine,
            primeUrl: String?,
            waitSelector: String?,
            extraWaitMs: Long,
            block: (fetchPage: (url: String) -> FetchResult) -> T,
        ): T {
            val isFirefox = engine == BrowserEngine.FIREFOX
            val ua = if (isFirefox) FIREFOX_UA else USER_AGENT
            val sessionContextOpts = Browser.NewContextOptions()
                .setUserAgent(ua).setLocale("de-DE").setViewportSize(1366, 768)
                .setExtraHTTPHeaders(mapOf(
                    "Accept-Language" to "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Upgrade-Insecure-Requests" to "1", "Cache-Control" to "max-age=0",
                ))
            val context = browserFor(engine).newContext(sessionContextOpts)
            return try {
                val page = context.newPage()
                if (engine == BrowserEngine.CHROMIUM) page.addInitScript(STEALTH_SCRIPT)
                page.route("**/*.{png,jpg,jpeg,gif,svg,webp,woff,woff2,ttf,eot}") { it.abort() }

                // Prime ONCE — subsequent pages in the same domain skip PoW
                if (primeUrl != null) {
                    log.debug("[{}] Session priming via {}", engine, primeUrl)
                    try {
                        page.navigate(primeUrl, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(20_000.0))
                        if (isChallengePage(page.url())) {
                            log.debug("[{}] Priming hit challenge, waiting...", engine)
                            waitForChallenge(page)
                        } else {
                            page.waitForTimeout(1_500.0)
                        }
                        log.info("[{}] Session primed — at {}", engine, page.url().take(60))
                    } catch (e: Exception) {
                        log.warn("[{}] Priming failed (continuing): {}", engine, e.message)
                    }
                }

                block { url ->
                    page.navigate(url, Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED).setTimeout(30_000.0))
                    if (isChallengePage(page.url())) {
                        log.debug("[{}] Challenge on page, waiting...", engine)
                        if (!waitForChallenge(page)) {
                            return@block FetchResult(html = "", statusCode = 403, finalUrl = page.url())
                        }
                    }
                    if (waitSelector != null) {
                        try {
                            page.waitForSelector(waitSelector, Page.WaitForSelectorOptions().setTimeout(15_000.0).setState(WaitForSelectorState.ATTACHED))
                        } catch (_: Exception) {}
                    }
                    if (extraWaitMs > 0) page.waitForTimeout(extraWaitMs.toDouble())
                    FetchResult(html = page.content(), statusCode = 200, finalUrl = page.url())
                }
            } finally {
                try { context.close() } catch (_: Exception) {}
            }
        }

        fun shutdown() {
            try { chromiumBrowser?.close() } catch (_: Exception) {}
            try { firefoxBrowser?.close() } catch (_: Exception) {}
            try { webkitBrowser?.close() } catch (_: Exception) {}
            try { playwrightInstance?.close() } catch (_: Exception) {}
        }
    }

    // Two fetches at a time, as before, each on a worker of its own.
    private val allWorkers = List(2) { Worker("playwright-$it") }
    private val idle = ArrayBlockingQueue<Worker>(allWorkers.size).apply { addAll(allWorkers) }

    private fun <T> withWorker(block: Worker.() -> T): T {
        val worker = idle.take()
        return try { worker.onItsThread(block) } finally { idle.put(worker) }
    }

    fun fetch(
        url: String,
        waitSelector: String? = null,
        waitTimeoutMs: Double = 15_000.0,
        extraWaitMs: Long = 0,
        waitNetworkIdle: Boolean = false,
        primeUrl: String? = null,
        engine: BrowserEngine = BrowserEngine.CHROMIUM,
    ): FetchResult = withWorker {
        fetch(url, waitSelector, waitTimeoutMs, extraWaitMs, waitNetworkIdle, primeUrl, engine)
    }

    /**
     * Opens one browser context, primes once (solves PoW/Argon2), then lets the caller
     * fetch multiple URLs reusing the same session. One worker is held for the whole session,
     * and [block] runs on its thread. Retries once on stale browser/Playwright errors.
     */
    fun <T> withSession(
        engine: BrowserEngine = BrowserEngine.CHROMIUM,
        primeUrl: String? = null,
        waitSelector: String? = null,
        extraWaitMs: Long = 2000,
        block: (fetchPage: (url: String) -> FetchResult) -> T,
    ): T = withWorker {
        try {
            withSessionInner(engine, primeUrl, waitSelector, extraWaitMs, block)
        } catch (e: Exception) {
            if (isPlaywrightStaleError(e)) {
                log.warn("[{}] Playwright stale in withSession, invalidating and retrying: {}", engine, e.message?.take(80))
                invalidateBrowser(engine)
                withSessionInner(engine, primeUrl, waitSelector, extraWaitMs, block)
            } else throw e
        }
    }

    private fun isChallengePage(url: String): Boolean =
        url.contains("/splashui/") || url.contains("/challenge?") || url.contains("challenge=1")

    /** Wait up to 30s for a JS challenge (PoW/Argon2) to auto-resolve. Returns true if resolved. */
    private fun waitForChallenge(page: Page): Boolean {
        for (i in 1..15) {
            page.waitForTimeout(2_000.0)
            if (!isChallengePage(page.url())) {
                log.debug("Challenge resolved after ~{}s → {}", i * 2, page.url().take(80))
                return true
            }
        }
        log.warn("Challenge NOT resolved after 30s, still at {}", page.url().take(80))
        return false
    }

    private fun isPlaywrightStaleError(e: Exception): Boolean {
        val msg = e.message ?: return false
        return msg.contains("Object doesn't exist") ||
            msg.contains("TargetClosedError") ||
            msg.contains("has been closed") ||
            msg.contains("call_adopt") ||
            msg.contains("Browser has been closed") ||
            msg.contains("ERR_ABORTED") ||
            msg.contains("frame was detached")
    }

    fun shutdown() {
        allWorkers.forEach { worker -> runCatching { worker.onItsThread { shutdown() } } }
    }
}

data class FetchResult(
    val html: String,
    val statusCode: Int,
    val finalUrl: String = "",
)
