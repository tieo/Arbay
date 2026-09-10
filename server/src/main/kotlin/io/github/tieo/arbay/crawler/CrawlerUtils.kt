package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.SuggestedTerm
import io.github.tieo.arbay.model.SearchQuery
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.delay
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

internal const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

internal fun String.encodeUrl(): String = java.net.URLEncoder.encode(this, "UTF-8")

private val fetchLog = org.slf4j.LoggerFactory.getLogger("FetchChain")

/** Randomized inter-page delay (150-600 ms) so paged fetches don't leave a fixed-interval trail. */
private fun fetchJitterMs(): Long = Random.nextLong(150, 600)

/** CoroutineContext element for emitting fetch-engine progress to the SSE stream */
class FetchProgressEmitter(val emit: suspend (stage: String) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<FetchProgressEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

/** CoroutineContext element for streaming a page's parsed listings to the SSE stream as soon as it
 *  is parsed, so a slow multi-page crawler (mobile.de under the stealth browser) surfaces its first
 *  page immediately instead of dumping everything at the end. Absent outside the streaming search,
 *  where [emitPartialResults] is a no-op and the crawler's return value is used as before. */
class PartialResultEmitter(val emit: suspend (listings: List<Listing>) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<PartialResultEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

/** Stream one just-parsed page of [listings] to the live result feed if a [PartialResultEmitter] is
 *  attached; a no-op otherwise. Crawlers call this after each page so results pipeline in. */
internal suspend fun emitPartialResults(listings: List<Listing>) {
    if (listings.isEmpty()) return
    coroutineContext[PartialResultEmitter]?.emit(listings)
}

/** CoroutineContext element collecting the related search terms a marketplace prints on its own
 *  results page ("Ähnliche Suchanfragen"). The market's own vocabulary for the thing searched for,
 *  which is what [QueryVariants] would otherwise have to infer. */
class SuggestedTermsEmitter(val emit: (terms: List<String>) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<SuggestedTermsEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

/** Report the related search terms found on a results page, if anyone is collecting them. Crawlers
 *  call this while parsing, so the terms cost no request of their own. */
internal suspend fun emitSuggestedTerms(terms: List<String>) {
    if (terms.isEmpty()) return
    coroutineContext[SuggestedTermsEmitter]?.emit(terms)
}

/** CoroutineContext element collecting the terms a market was actually searched with: the one
 *  handed to it, plus every other name for the thing that the search was allowed to follow up on.
 *  Reported to the app, which shows them per market — a term nobody can see is a term nobody can
 *  hold the answer to. */
class TermsUsedEmitter(val emit: (term: String) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TermsUsedEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

internal suspend fun emitTermUsed(term: String) {
    coroutineContext[TermsUsedEmitter]?.emit(term)
}

/**
 * Whether a market's answer looks like a parser reading the wrong thing.
 *
 * Two of these ran for months: eBay's "New Listing" flag and its "opens in a new window or tab"
 * line, each read as the title of every listing that carried it. Nothing noticed, because a title
 * that is not the listing's title still parses, and the listings were then dropped one by one for
 * carrying none of the words searched for. A market answering with the same title over and over is
 * the shape of that, whatever the cause, and it is worth saying out loud.
 */
fun repeatedTitleReport(listings: List<Listing>): String? {
    if (listings.size < MIN_ANSWER_TO_JUDGE_TITLES) return null
    val (title, count) = listings
        .groupingBy { it.title.trim().lowercase() }
        .eachCount()
        .maxByOrNull { it.value } ?: return null
    if (count < MIN_ANSWER_TO_JUDGE_TITLES) return null
    if (count.toDouble() / listings.size < SAME_TITLE_SHARE) return null
    return "$count of ${listings.size} listings are titled \"$title\" — the title is being read " +
        "off the wrong part of the card"
}

private const val MIN_ANSWER_TO_JUDGE_TITLES = 5
private const val SAME_TITLE_SHARE = 0.5

/**
 * A market saying it answered by matching the search to one of its own categories.
 *
 * Idealo answers "laptop" with the Laptop category, whose products are MacBooks and ThinkPads that
 * never write the word. That is the market having searched, and having answered well; judged on
 * words alone it looks exactly like a market that ignored the question, and the whole answer was
 * being thrown away.
 */
class CategoryAnswerEmitter(val emit: () -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CategoryAnswerEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

internal suspend fun emitAnsweredFromCategory() {
    coroutineContext[CategoryAnswerEmitter]?.emit()
}

/** CoroutineContext element collecting the app's verdict on each other word a market printed:
 *  what it is, why, and — once a word has actually been searched — what it added. A word emitted
 *  twice is the same word with its outcome filled in, so a collector keeps the last of each. */
class TermVerdictEmitter(val emit: (term: SuggestedTerm) -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<TermVerdictEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

internal suspend fun emitTermVerdict(term: SuggestedTerm) {
    coroutineContext[TermVerdictEmitter]?.emit(term)
}

/** CoroutineContext element notified when a stealth fetch exposes its live browser for a human to
 *  solve a captcha in place (same IP + fingerprint the token binds to), so the search route can push
 *  the solve link to the client. */
class CaptchaInteractiveEmitter(val emit: suspend () -> Unit) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<CaptchaInteractiveEmitter>
    override val key: CoroutineContext.Key<*> = Key
}

/** Signal that the current crawl has put a captcha up for interactive solving, if a
 *  [CaptchaInteractiveEmitter] is attached; a no-op otherwise. */
internal suspend fun emitCaptchaInteractive() {
    coroutineContext[CaptchaInteractiveEmitter]?.emit()
}

/** Runs the page-by-page crawl every list crawler shares: fetch and parse one page via [fetchPage],
 *  stopping when a page comes back empty, adds nothing new, or the per-platform cap is reached.
 *  Deduplicates by [Listing.externalId] (keeping first-seen order), streams each page's freshly
 *  parsed listings to the live feed as they arrive, and returns the full ordered set. A block on the
 *  first page propagates (the platform failed); a block on a later page just stops paging and keeps
 *  what was already collected. Crawlers supply only how to fetch+parse a single page number; the
 *  loop, dedup, cap, block handling and streaming live in this one place. [page] counts from
 *  [SearchQuery.startPage]. */
/** One page, retried once on a thrown error before it is believed failed. A single transient
 *  403/503 on page two used to end pagination and silently drop the rest of a platform's results;
 *  one backed-off retry recovers those. An empty page is NOT retried — it is the normal end of
 *  results, and retrying it would double the tail fetch for every platform on every search.
 *  Returns null when the page could not be fetched after the retry: the first page rethrows (the
 *  platform genuinely failed), a later one just stops paging and keeps what was already collected. */
private suspend fun fetchPageWithRetry(
    page: Int,
    isFirstPage: Boolean,
    fetchPage: suspend (page: Int) -> List<Listing>,
): List<Listing>? {
    try {
        return fetchPage(page)
    } catch (e: CrawlerBlockedException) {
        // A hard block (captcha / IP ban) is not transient; escalating fetch tiers already ran.
        // Retrying would only deepen the block, so surface it immediately.
        if (isFirstPage) throw e
        return null
    } catch (_: Exception) {
        // A transient network/HTTP error: back off and try once more.
    }
    delay(700L + fetchJitterMs())
    return try {
        fetchPage(page)
    } catch (e: Exception) {
        if (isFirstPage) throw e
        null
    }
}

/** How many pages this search may fetch: the search's own limit when it set one, else the
 *  configured search depth, never more than [cap] for a site that blocks past a few pages. */
internal fun SearchQuery.pageLimit(cap: Int = Int.MAX_VALUE): Int =
    (maxPages ?: CrawlerConfig.current.maxPages).coerceAtMost(cap)

internal suspend fun paginate(
    query: SearchQuery,
    fetchPage: suspend (page: Int) -> List<Listing>,
): List<Listing> {
    val maxPages = query.pageLimit()
    val seen = LinkedHashMap<String, Listing>()
    for (offset in 0 until maxPages) {
        val listings = fetchPageWithRetry(query.startPage + offset, isFirstPage = offset == 0, fetchPage)
            ?: break
        if (listings.isEmpty()) break
        emitPartialResults(listings)
        val newIds = listings.count { it.externalId !in seen }
        listings.forEach { seen.putIfAbsent(it.externalId, it) }
        if (newIds == 0) break
        if (seen.size >= CrawlerConfig.current.maxResultsPerPlatform) break
    }
    return seen.values.toList()
}

/** Validate HTML — throws if it's a block/captcha page */
internal fun validateHtml(html: String, platformName: String) {
    if (html.length < 500)
        throw CrawlerBlockedException("$platformName: empty response", ErrorType.BLOCKED_403, html = html)
    if (detectCaptcha(html))
        throw CrawlerBlockedException("$platformName captcha", ErrorType.CAPTCHA, html = html)
    if (html.length < 1000 && (html.contains("bot") || html.contains("challenge") || html.contains("blocked")))
        throw CrawlerBlockedException("$platformName likely blocked (small response)", ErrorType.BLOCKED_403, html = html)
    detectBlockPage(html, platformName)?.let { throw it.withEvidence(html = html) }
}

/** The same refusal, carrying the page that showed it. */
internal fun CrawlerBlockedException.withEvidence(
    url: String? = null,
    html: String? = null,
    statusCode: Int? = null,
): CrawlerBlockedException = CrawlerBlockedException(
    message = message ?: "blocked",
    errorType = errorType,
    url = url ?: this.url,
    html = html ?: this.html,
    statusCode = statusCode ?: this.statusCode,
)

/** Validate browser result — checks both final URL and HTML */
internal fun validateBrowserResult(result: FetchResult, platformName: String): String {
    val html = result.html
    val finalUrl = result.finalUrl
    if (finalUrl.contains("/splashui/") || finalUrl.contains("/challenge?") || finalUrl.contains("challenge=1"))
        throw CrawlerBlockedException(
            "$platformName: challenge redirect ($finalUrl)", ErrorType.CAPTCHA, url = finalUrl, html = html,
        )
    if (detectCaptcha(html))
        throw CrawlerBlockedException(
            "$platformName: captcha in browser response", ErrorType.CAPTCHA, url = finalUrl, html = html,
        )
    detectBlockPage(html, platformName)?.let { throw it.withEvidence(url = finalUrl, html = html) }
    if (html.length < 500)
        throw CrawlerBlockedException(
            "$platformName: browser returned empty page", ErrorType.BLOCKED_403, url = finalUrl, html = html,
        )
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
 *
 * With browserOnly the HTTP and CurlCffi steps are skipped and the chain starts at
 * Chromium, for platforms whose bot protection (Akamai on mobile.de) blocks every
 * non-browser engine and the first two steps only waste time.
 */
internal suspend fun fetchWithFallback(
    client: HttpClient,
    url: String,
    platformName: String,
    waitSelector: String? = null,
    extraWaitMs: Long = 2000,
    primeUrl: String? = null,
    waitNetworkIdle: Boolean = false,
    browserOnly: Boolean = false,
): String {
    val errors = mutableListOf<String>()
    val emitter = coroutineContext[FetchProgressEmitter.Key]

    // Hard cutoff: refuse once a platform has hit the per-window request ceiling, so a runaway
    // can't hammer a site into flagging our IP.
    if (RequestMonitor.overBudget(platformName))
        throw CrawlerBlockedException("$platformName: request cutoff reached, skipping", ErrorType.RATE_LIMITED_429)

    // Randomized pause before each page fetch. A fixed cadence across 11 platforms is itself
    // a bot signature; the jitter spreads the burst and varies the inter-request gap.
    RequestMonitor.recordRequest(platformName)
    delay(fetchJitterMs())

    if (!browserOnly) {
        // === Step 1: Plain HTTP ===
        emitter?.let { it.emit("HTTP") }
        try {
            val html = fetchHttp(client, url, platformName)
            validateHtml(html, platformName)
            RequestMonitor.recordTier(platformName, "HTTP")
            return html
        } catch (e: Exception) {
            errors.add("HTTP: ${e.message?.take(60)}")
            fetchLog.debug("[{}] HTTP failed: {}", platformName, e.message?.take(80))
            if (!isRetryable(e)) throw e
        }

        // === Step 2: rnet (current Chrome TLS + post-quantum key share) ===
        emitter?.let { it.emit("Rnet") }
        try {
            val html = RnetClient.fetch(url, primeUrl = primeUrl)
            validateHtml(html, platformName)
            RequestMonitor.recordTier(platformName, "Rnet")
            return html
        } catch (e: Exception) {
            errors.add("Rnet: ${e.message?.take(60)}")
            fetchLog.debug("[{}] Rnet failed: {}", platformName, e.message?.take(80))
        }

        // === Step 3: CurlCffi (Chrome TLS fingerprint) ===
        emitter?.let { it.emit("CurlCffi") }
        try {
            val html = CurlCffiClient.fetch(url, primeUrl = primeUrl)
            validateHtml(html, platformName)
            RequestMonitor.recordTier(platformName, "CurlCffi")
            return html
        } catch (e: Exception) {
            errors.add("CurlCffi: ${e.message?.take(60)}")
            fetchLog.debug("[{}] CurlCffi failed: {}", platformName, e.message?.take(80))
        }
    }

    // === Step 3: Chromium non-headless via Xvfb ===
    emitter?.let { it.emit("Chromium") }
    try {
        val result = HeadlessBrowser.fetch(
            url = url, waitSelector = waitSelector, extraWaitMs = extraWaitMs,
            waitNetworkIdle = waitNetworkIdle, primeUrl = primeUrl,
            engine = BrowserEngine.CHROMIUM,
        )
        return validateBrowserResult(result, platformName).also { RequestMonitor.recordTier(platformName, "Browser") }
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
        return validateBrowserResult(result, platformName).also { RequestMonitor.recordTier(platformName, "Browser") }
    } catch (e: Exception) {
        errors.add("Firefox: ${e.message?.take(60)}")
        fetchLog.debug("[{}] Firefox failed: {}", platformName, e.message?.take(80))
    }

    // All engines failed
    RequestMonitor.recordBlock(platformName)
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
        msg.contains("connection reset") || msg.contains("connection refused") || msg.contains("eof") ||
        // A redirect loop ("Max send count 20 exceeded") means the site bounces this client over a
        // cookie or consent hop it cannot satisfy. Another engine carrying a browser's cookie jar
        // usually loads the same URL fine, so the chain must continue rather than abort here.
        msg.contains("max send count")
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
