package io.github.tieo.arbay.crawler

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

private val fetchLog = org.slf4j.LoggerFactory.getLogger("FetchChain")

/** CoroutineContext element for emitting fetch-engine progress to the SSE stream */
class FetchProgressEmitter(val emit: suspend (stage: String) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<FetchProgressEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

/** Validate HTML — throws if it's a block/captcha page */
internal fun validateHtml(html: String, platformName: String) {
    if (html.length < 500) throw CrawlerBlockedException("$platformName: empty response", ErrorType.BLOCKED_403)
    val finalUrl = "" // only available from browser fetches
    if (detectCaptcha(html)) throw CrawlerBlockedException("$platformName captcha", ErrorType.CAPTCHA)
    if (html.length < 1000 && (html.contains("bot") || html.contains("challenge") || html.contains("blocked")))
        throw CrawlerBlockedException("$platformName likely blocked (small response)", ErrorType.BLOCKED_403)
    detectBlockPage(html, platformName)?.let { throw it }
}

/** Validate browser result — checks both final URL and HTML */
internal fun validateBrowserResult(result: FetchResult, platformName: String): String {
    val html = result.html
    val finalUrl = result.finalUrl
    if (finalUrl.contains("/splashui/") || finalUrl.contains("/challenge?") || finalUrl.contains("challenge=1"))
        throw CrawlerBlockedException("$platformName: challenge redirect ($finalUrl)", ErrorType.CAPTCHA)
    if (detectCaptcha(html))
        throw CrawlerBlockedException("$platformName: captcha in browser response", ErrorType.CAPTCHA)
    detectBlockPage(html, platformName)?.let { throw it }
    if (html.length < 500)
        throw CrawlerBlockedException("$platformName: browser returned empty page", ErrorType.BLOCKED_403)
    return html
}

/**
 * General-purpose fetch with a full fallback chain. All crawlers should use this.
 *
 * Fallback order:
 * 1. HTTP GET (fast, no JS — works for most platforms)
 * 2. CurlCffi (Chrome TLS fingerprint — bypasses Cloudflare/Akamai)
 * 3. Chromium non-headless via Xvfb (PoW/JS challenge solving)
 * 4. Firefox headless (different fingerprint, evades Chromium-specific detection)
 *
 * Each step is tried only if the previous step failed with a retryable error.
 */
internal suspend fun fetchWithFallback(
    client: HttpClient,
    url: String,
    platformName: String,
    waitSelector: String? = null,
    extraWaitMs: Long = 2000,
    primeUrl: String? = null,
    waitNetworkIdle: Boolean = false,
): String {
    val errors = mutableListOf<String>()
    val emitter = coroutineContext[FetchProgressEmitter.Key]

    // === Step 1: Plain HTTP ===
    emitter?.let { it.emit("HTTP") }
    try {
        val html = fetchHttp(client, url, platformName)
        validateHtml(html, platformName)
        return html
    } catch (e: Exception) {
        errors.add("HTTP: ${e.message?.take(60)}")
        fetchLog.debug("[{}] HTTP failed: {}", platformName, e.message?.take(80))
        if (!isRetryable(e)) throw e
    }

    // === Step 2: CurlCffi (Chrome TLS fingerprint) ===
    emitter?.let { it.emit("CurlCffi") }
    try {
        val html = CurlCffiClient.fetch(url, primeUrl = primeUrl)
        validateHtml(html, platformName)
        return html
    } catch (e: Exception) {
        errors.add("CurlCffi: ${e.message?.take(60)}")
        fetchLog.debug("[{}] CurlCffi failed: {}", platformName, e.message?.take(80))
    }

    // === Step 3: Chromium non-headless via Xvfb ===
    emitter?.let { it.emit("Chromium") }
    try {
        val result = HeadlessBrowser.fetch(
            url = url, waitSelector = waitSelector, extraWaitMs = extraWaitMs,
            waitNetworkIdle = waitNetworkIdle, primeUrl = primeUrl,
            engine = BrowserEngine.CHROMIUM,
        )
        return validateBrowserResult(result, platformName)
    } catch (e: Exception) {
        errors.add("Chromium: ${e.message?.take(60)}")
        fetchLog.debug("[{}] Chromium failed: {}", platformName, e.message?.take(80))
    }

    // === Step 4: Firefox headless ===
    emitter?.let { it.emit("Firefox") }
    try {
        val result = HeadlessBrowser.fetch(
            url = url, waitSelector = waitSelector, extraWaitMs = extraWaitMs,
            waitNetworkIdle = waitNetworkIdle, primeUrl = primeUrl,
            engine = BrowserEngine.FIREFOX,
        )
        return validateBrowserResult(result, platformName)
    } catch (e: Exception) {
        errors.add("Firefox: ${e.message?.take(60)}")
        fetchLog.debug("[{}] Firefox failed: {}", platformName, e.message?.take(80))
    }

    // All engines failed
    throw CrawlerBlockedException(
        "$platformName blocked by all engines: ${errors.joinToString(" → ")}",
        ErrorType.CAPTCHA,
    )
}

/** Convenience: fetch with browser only (skip HTTP/CurlCffi steps).
 *  Retries once on TargetClosedError (browser process died mid-session). */
internal fun fetchWithBrowser(
    url: String,
    platformName: String,
    waitSelector: String? = null,
    extraWaitMs: Long = 2000,
    waitNetworkIdle: Boolean = false,
    primeUrl: String? = null,
    waitTimeoutMs: Double = 15_000.0,
    engine: BrowserEngine = BrowserEngine.CHROMIUM,
): String {
    fun doFetch() = HeadlessBrowser.fetch(
        url = url, waitSelector = waitSelector, waitTimeoutMs = waitTimeoutMs,
        extraWaitMs = extraWaitMs, waitNetworkIdle = waitNetworkIdle,
        primeUrl = primeUrl, engine = engine,
    )
    val result = try { doFetch() } catch (e: Exception) {
        val msg = e.message ?: ""
        if (msg.contains("Object doesn't exist") || msg.contains("TargetClosedError") ||
            msg.contains("has been closed") || msg.contains("call_adopt") ||
            msg.contains("ERR_ABORTED") || msg.contains("frame was detached")) {
            // Browser already invalidated by fetch()'s catch — just retry with fresh instance
            val log = org.slf4j.LoggerFactory.getLogger("fetchWithBrowser")
            log.warn("Playwright stale for {}, retrying: {}", platformName, msg.take(60))
            doFetch()
        } else throw e
    }
    return validateBrowserResult(result, platformName)
}

internal suspend fun fetchHttp(client: HttpClient, url: String, platformName: String): String {
    val response = client.get(url) {
        headers {
            append("User-Agent", USER_AGENT)
            append("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            append("Accept-Language", "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
            append("Upgrade-Insecure-Requests", "1")
            append("Sec-Fetch-Dest", "document")
            append("Sec-Fetch-Mode", "navigate")
            append("Sec-Fetch-Site", "none")
            append("Sec-Fetch-User", "?1")
            append("Cache-Control", "max-age=0")
        }
    }
    if (response.status.value == 429) {
        delay(3_000L)
        val retry = client.get(url) {
            headers { append("User-Agent", USER_AGENT) }
        }
        if (retry.status.value != 200)
            throw CrawlerBlockedException("$platformName HTTP ${retry.status.value}", classifyHttpError(retry))
        return retry.bodyAsText()
    }
    if (response.status.value != 200) {
        throw CrawlerBlockedException("$platformName HTTP ${response.status.value}", classifyHttpError(response))
    }
    return response.bodyAsText()
}

private fun isRetryable(e: Exception): Boolean {
    val msg = e.message?.lowercase() ?: ""
    return e is CrawlerBlockedException ||
        e is io.ktor.client.plugins.HttpRequestTimeoutException ||
        e is io.ktor.client.network.sockets.ConnectTimeoutException ||
        msg.contains("timeout") || msg.contains("prematurely closed") ||
        msg.contains("connection reset") || msg.contains("connection refused") || msg.contains("eof")
}

internal fun classifyHttpError(response: HttpResponse): ErrorType {
    return when (response.status.value) {
        401 -> ErrorType.AUTH_REQUIRED_401
        403 -> ErrorType.BLOCKED_403
        429 -> ErrorType.RATE_LIMITED_429
        503 -> ErrorType.SERVICE_UNAVAILABLE_503
        else -> ErrorType.UNKNOWN
    }
}

internal fun classifyException(e: Exception): ErrorType {
    val msg = e.message?.lowercase() ?: ""
    return when {
        e is io.ktor.client.network.sockets.ConnectTimeoutException -> ErrorType.TIMEOUT
        e is io.ktor.client.plugins.HttpRequestTimeoutException -> ErrorType.TIMEOUT
        msg.contains("timeout") -> ErrorType.TIMEOUT
        msg.contains("connection refused") || msg.contains("unreachable") -> ErrorType.NETWORK_ERROR
        else -> ErrorType.UNKNOWN
    }
}

/**
 * Detect various block pages that aren't captchas (IP blocks, access denied, Cloudflare challenges).
 */
internal fun detectBlockPage(html: String, platformName: String): CrawlerBlockedException? {
    val lower = html.lowercase()
    if (html.length > 50_000) return null // Real content pages are large
    return when {
        lower.contains("zugriff verweigert") || lower.contains("access denied") ->
            CrawlerBlockedException("$platformName: access denied", ErrorType.BLOCKED_403)
        lower.contains("ip-adresse wurde blockiert") || lower.contains("ip address is blocked") ->
            CrawlerBlockedException("$platformName: IP blocked", ErrorType.BLOCKED_403)
        lower.contains("ich bin kein roboter") && lower.contains("immobilienscout") ->
            CrawlerBlockedException("$platformName: robot check", ErrorType.CAPTCHA)
        // Akamai Bot Manager challenge — JS-only page with "sec-if-cpt" / "sec-bc" markers.
        // mobile.de, and other Akamai-protected sites return this when fingerprint fails.
        lower.contains("sec-if-cpt-container") || lower.contains("sec-bc-tile") ->
            CrawlerBlockedException("$platformName: Akamai challenge", ErrorType.CAPTCHA)
        lower.contains("__cf_chl") ||
            lower.contains("sicherheitsüberprüfung wird durchgeführt") ||
            lower.contains("sichere verbindung wird") ||
            lower.contains("checking your browser") ||
            lower.contains("enable javascript and cookies") ||
            (lower.contains("just a moment") && lower.contains("cloudflare")) ->
            CrawlerBlockedException("$platformName: Cloudflare challenge", ErrorType.CAPTCHA)
        lower.contains("something has gone wrong") && html.length < 10_000 ->
            CrawlerBlockedException("$platformName: error page", ErrorType.UNKNOWN)
        lower.contains("tut uns leid") && html.length < 10_000 ->
            CrawlerBlockedException("$platformName: bot detection (Tut uns Leid)", ErrorType.BLOCKED_403)
        else -> null
    }
}

internal fun detectCaptcha(html: String): Boolean {
    val lower = html.lowercase()

    // Standard captcha widgets (small pages only, to avoid false positives on normal pages with captcha JS)
    val hasCaptchaForm = lower.contains("g-recaptcha") ||
        lower.contains("h-captcha") ||
        lower.contains("cf-challenge-running") ||
        lower.contains("cf-turnstile")
    val hasBlockingText = lower.contains("are you a human") ||
        lower.contains("bitte bestätige, dass du kein roboter bist") ||
        lower.contains("please verify you are not a robot")
    val isSmallPage = html.length < 20_000
    if ((hasCaptchaForm || hasBlockingText) && isSmallPage) return true

    // eBay-specific challenge page (can be large because it includes full site chrome)
    if (lower.contains("pagename:'challengeget'") ||
        lower.contains("ihr browser wird geprüft") ||
        lower.contains("your browser is being verified")) return true

    // Amazon bot/CAPTCHA detection — returned as HTTP 200, any page size
    if (lower.contains("validatecaptcha") ||
        lower.contains("<title>robot check</title>") ||
        lower.contains("enter the characters you see below") ||
        lower.contains("geben sie die zeichen ein")) return true

    return false
}
