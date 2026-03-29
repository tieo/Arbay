package io.github.tieo.arbay.crawler

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitForSelectorState
import com.microsoft.playwright.options.WaitUntilState
import org.slf4j.LoggerFactory
import java.util.concurrent.Semaphore

enum class BrowserEngine { CHROMIUM, FIREFOX, WEBKIT }

object HeadlessBrowser {
    private val log = LoggerFactory.getLogger(HeadlessBrowser::class.java)

    // Allow 2 concurrent browser contexts — balance between speed and stability
    private val semaphore = Semaphore(2)

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

    @Volatile private var playwrightInstance: Playwright? = null
    private val playwright: Playwright get() = synchronized(browserLock) {
        playwrightInstance ?: run {
            log.info("Initializing Playwright...")
            Playwright.create().also { playwrightInstance = it }
        }
    }

    private val xvfbStarted: Boolean by lazy {
        try {
            ProcessBuilder("Xvfb", ":99", "-screen", "0", "1366x768x24", "-nolisten", "tcp")
                .redirectErrorStream(true).start()
            Thread.sleep(500)
            log.info("Started Xvfb on :99")
            true
        } catch (e: Exception) {
            log.warn("Xvfb not available: ${e.message}")
            false
        }
    }

    // Browser instances — recreated if the process dies
    @Volatile private var chromiumBrowser: Browser? = null
    @Volatile private var firefoxBrowser: Browser? = null
    @Volatile private var webkitBrowser: Browser? = null
    private val browserLock = Any()

    private val chromiumArgs = listOf(
        "--disable-blink-features=AutomationControlled",
        "--disable-dev-shm-usage", "--no-sandbox", "--disable-infobars",
        "--disable-background-timer-throttling",
        "--disable-backgrounding-occluded-windows",
        "--disable-renderer-backgrounding", "--disable-gpu",
    )

    private fun launchChromium(): Browser {
        xvfbStarted // ensure Xvfb is started
        if (xvfbStarted) {
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
                log.warn("Non-headless Chromium failed, falling back to headless: {}", e.message?.take(80))
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

    private fun invalidateBrowser(engine: BrowserEngine) = synchronized(browserLock) {
        when (engine) {
            BrowserEngine.CHROMIUM -> { try { chromiumBrowser?.close() } catch (_: Exception) {}; chromiumBrowser = null }
            BrowserEngine.FIREFOX -> { try { firefoxBrowser?.close() } catch (_: Exception) {}; firefoxBrowser = null }
            BrowserEngine.WEBKIT -> { try { webkitBrowser?.close() } catch (_: Exception) {}; webkitBrowser = null }
        }
    }

    private fun browserFor(engine: BrowserEngine): Browser = synchronized(browserLock) {
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
        if (existing != null && existing.isConnected) return@synchronized existing

        try {
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
        semaphore.acquire()
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
            semaphore.release()
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

    /**
     * Opens one browser context, primes once (solves PoW/Argon2), then lets the caller
     * fetch multiple URLs reusing the same session. Semaphore held for entire duration.
     * Retries once on stale browser/Playwright errors.
     */
    fun <T> withSession(
        engine: BrowserEngine = BrowserEngine.CHROMIUM,
        primeUrl: String? = null,
        waitSelector: String? = null,
        extraWaitMs: Long = 2000,
        block: (fetchPage: (url: String) -> FetchResult) -> T,
    ): T {
        semaphore.acquire()
        return try {
            withSessionInner(engine, primeUrl, waitSelector, extraWaitMs, block)
        } catch (e: Exception) {
            if (isPlaywrightStaleError(e)) {
                log.warn("[{}] Playwright stale in withSession, invalidating and retrying: {}", engine, e.message?.take(80))
                invalidateBrowser(engine)
                withSessionInner(engine, primeUrl, waitSelector, extraWaitMs, block)
            } else throw e
        } finally {
            semaphore.release()
        }
    }

    private fun <T> withSessionInner(
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
        try { chromiumBrowser?.close() } catch (_: Exception) {}
        try { firefoxBrowser?.close() } catch (_: Exception) {}
        try { webkitBrowser?.close() } catch (_: Exception) {}
        try { playwrightInstance?.close() } catch (_: Exception) {}
    }
}

data class FetchResult(
    val html: String,
    val statusCode: Int,
    val finalUrl: String = "",
)
