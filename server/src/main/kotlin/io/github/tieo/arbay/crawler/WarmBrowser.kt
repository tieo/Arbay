package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * A Chrome a site is read through, kept running by warm_browser.py: one per site that turns away
 * anything not looking like a person's browser, for the life of the server, on a display of its
 * own, its profile in the data directory so its cookies outlive restarts. It reads as many pages at
 * once as it has [tabs], one by default, which is also the pace such a site lets through.
 */
open class WarmBrowser(
    private val name: String,
    private val display: Int,
    private val profileDir: String,
    private val logName: String,
    /** Text only the site's error page carries. */
    private val errors: List<String> = emptyList(),
    /** The buttons of a consent screen the site puts before its pages. */
    private val accept: List<String> = emptyList(),
    /** Pages read at once, each in a tab of its own. */
    private val tabs: Int = 1,
) {
    private val log = LoggerFactory.getLogger("${WarmBrowser::class.java.name}.$name")
    private val json = Json { ignoreUnknownKeys = true }
    private val free = Semaphore(tabs)
    private val starting = Mutex()

    private var xvfb: Process? = null
    @Volatile private var process: Process? = null
    private var input: BufferedWriter? = null
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val ids = AtomicLong()

    /** A page as the browser read it: [page] says whether it is the page asked for ("content"), the
     *  site's error page, a challenge, the site refusing the browser ("refused"), or none of them. */
    class Page(val html: String, val url: String, val page: String)

    /**
     * Reads [url] and waits until one of [markers] shows in it, the site's error page shows, or
     * [timeoutS] passes. Null where the browser could not be started or did not answer, which
     * leaves the caller its other ways in.
     */
    suspend fun get(url: String, markers: List<String>, timeoutS: Int = 30, refusals: List<String> = emptyList(), tick: List<String> = emptyList(), solve: String? = null): Page? {
        val request = mapOf(
            "op" to JsonPrimitive("get"), "url" to JsonPrimitive(url), "timeout" to JsonPrimitive(timeoutS),
            "markers" to JsonArray(markers.map(::JsonPrimitive)),
            "errors" to JsonArray(errors.map(::JsonPrimitive)),
            "accept" to JsonArray(accept.map(::JsonPrimitive)),
            "refusals" to JsonArray(refusals.map(::JsonPrimitive)),
            "tick" to JsonArray(tick.map(::JsonPrimitive)),
            "solve" to JsonPrimitive(solve.orEmpty()),
        )
        val answer = ask(request, timeoutS + 30L) ?: run {
            log.warn("{} browser did not answer for {}", name, url)
            return null
        }
        // Any page the site answers with, its error page too, is the site answering: the crawl's time
        // limit is there for a crawl that hangs, and one eBay keeps refusing and asking again is not.
        kotlin.coroutines.coroutineContext[CrawlClock]?.answered()
        if (answer["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            log.warn("{} browser failed {}: {}", name, url, answer["error"]?.jsonPrimitive?.contentOrNull)
            if (answer["stuck"]?.jsonPrimitive?.booleanOrNull == true) stop()
            return null
        }
        return Page(
            html = answer["html"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            url = answer["url"]?.jsonPrimitive?.contentOrNull ?: url,
            page = answer["page"]?.jsonPrimitive?.contentOrNull ?: "unknown",
        )
    }

    /**
     * Where each of a site's redirects [urls] leads: the first address off [leaving] it reaches in
     * this browser, one after another, each given [timeoutS]. Those that led nowhere in time are left
     * out, and all are when the browser does not answer.
     */
    suspend fun resolve(urls: List<String>, leaving: String, timeoutS: Int = 10): Map<String, String> {
        if (urls.isEmpty()) return emptyMap()
        val request = mapOf(
            "op" to JsonPrimitive("resolve"), "urls" to JsonArray(urls.map(::JsonPrimitive)),
            "leaving" to JsonPrimitive(leaving), "timeout" to JsonPrimitive(timeoutS),
        )
        val answer = ask(request, timeoutS.toLong() * urls.size + 30) ?: run {
            log.warn("{} browser did not resolve {} redirects", name, urls.size)
            return emptyMap()
        }
        if (answer["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            log.warn("{} browser failed to resolve {} redirects: {}", name, urls.size, answer["error"]?.jsonPrimitive?.contentOrNull)
            return emptyMap()
        }
        return (answer["to"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
    }

    /**
     * Sends [request] once a tab is free and waits for its answer, [limitS] at most: a browser that
     * answers nothing in that time is ended, which ends every read it had, and started again on the
     * next. Null where it did not answer.
     */
    private suspend fun ask(request: Map<String, JsonElement>, limitS: Long): JsonObject? {
        // One browser serves every search of its site, and a crawl waiting its turn is not its
        // market failing to answer: eBay's five sites share this one, and four waited out their
        // whole time limit behind the fifth.
        val clock = kotlin.coroutines.coroutineContext[CrawlClock]
        if (clock != null) clock.waitingOnOthers { free.acquire() } else free.acquire()
        try {
            val id = ids.incrementAndGet()
            val reply = CompletableDeferred<JsonObject>()
            pending[id] = reply
            try {
                withContext(Dispatchers.IO) {
                    starting.withLock { ensureRunning() }
                    val line = JsonObject(request + ("id" to JsonPrimitive(id))).toString()
                    synchronized(this@WarmBrowser) { input!!.apply { write(line); newLine(); flush() } }
                }
                return withTimeoutOrNull(limitS * 1000) { reply.await() } ?: run { stop(); null }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("{} browser: {}", name, e.message)
                stop()
                return null
            } finally {
                pending.remove(id)
            }
        } finally {
            free.release()
        }
    }

    /** Starts the browser ahead of its first page, so that page waits for no start. */
    suspend fun warm() {
        withContext(Dispatchers.IO) { starting.withLock { ensureRunning() } }
    }

    private fun ensureRunning() {
        if (process?.isAlive == true) return
        ensureXvfb()
        val script = File(StealthBrowserClient.scriptDir, "warm_browser.py")
        val started = ProcessBuilder("python3", script.absolutePath)
            .directory(StealthBrowserClient.scriptDir)
            .redirectError(ProcessBuilder.Redirect.appendTo(DataDir.file("logs/$logName").apply { parentFile.mkdirs() }))
            .also {
                it.environment()["DISPLAY"] = ":$display"
                it.environment()["ARBAY_BROWSER_PROFILE"] = DataDir.file(profileDir).absolutePath
                it.environment()["ARBAY_BROWSER_TABS"] = tabs.toString()
                // The puzzle readers' models: CLIP for listing photos is already kept there.
                it.environment()["ARBAY_MODELS_DIR"] = DataDir.models.absolutePath
            }
            .start()
        val output = started.inputStream.bufferedReader()
        val ready = output.readLine() ?: run {
            killTree(started)
            throw IllegalStateException("$name browser exited on start; see logs/$logName")
        }
        process = started
        input = started.outputStream.bufferedWriter()
        log.info("{} browser started: {}", name, ready)
        // Answers come in the order their pages finish, each carrying its request's id.
        Thread({
            try {
                while (true) {
                    val line = output.readLine() ?: break
                    val answer = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    answer["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { pending[it]?.complete(answer) }
                }
            } catch (_: Exception) {
            }
            // The browser ended: every read it still had fails now rather than at its time limit.
            if (process === started) process = null
            pending.values.forEach { it.completeExceptionally(IllegalStateException("$name browser exited")) }
        }, "$name-browser-answers").apply { isDaemon = true }.start()
    }

    private fun ensureXvfb() {
        if (xvfb?.isAlive == true) return
        File("/tmp/.X$display-lock").delete()
        File("/tmp/.X11-unix/X$display").delete()
        xvfb = ProcessBuilder("Xvfb", ":$display", "-screen", "0", "1280x900x24", "-nolisten", "tcp")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        Thread.sleep(500)
    }

    fun stop() {
        val p = process ?: return
        process = null
        runCatching { input?.close() }
        if (!p.waitFor(5, TimeUnit.SECONDS)) killTree(p)
        input = null
    }
}

/**
 * The Chrome every eBay page is read through. eBay's edge serves its error page to every client
 * that does not look like a person's browser, and a freshly started Chrome is one of them; a Chrome
 * that keeps its profile and reads one page after another is not.
 */
object EbayBrowser : WarmBrowser(
    "eBay", 96, "ebay-profile", "ebay-browser.log", errors = listOf("went wrong"),
    // Five eBay sites, each search word's other spellings and every search running at once read
    // through it, and a search waiting its turn here has its clock stopped: with one tab, two saved
    // searches of a dozen spellings each held all four of the server's search slots for twenty
    // minutes, and every other search queued as "Server busy".
    tabs = 3,
)

/**
 * The Chrome the search engines that refuse a plain request are asked through. Google has answered
 * only browsers that run its scripts since 2025, and SearXNG's Google and Qwant got "access denied"
 * from the server for that, while a person's Chrome on the same address searches normally. Its
 * consent screen is accepted once and remembered in the profile.
 */
object SearchBrowser : WarmBrowser(
    "search", 91, "search-profile", "search-browser.log",
    accept = listOf("Alle akzeptieren", "Accept all", "Akzeptieren", "Tout accepter", "Accetta tutto"),
    // Each web search asks five engines through it, and two searches at once queued every engine
    // of the second behind the first's past the search's deadline.
    tabs = 3,
)

/**
 * The Chromes shop pages are read through once their scripts ran: apart from [SearchBrowser], so a
 * shop page that hangs until its time runs out, and gets its browser restarted, holds up no search
 * engine (Duschmeister's own search did that on every web search), and several of them, since a
 * web search renders a handful of pages and one browser read them one after another for over a
 * minute.
 */
object RenderBrowsers {
    private val pool = kotlinx.coroutines.channels.Channel<WarmBrowser>(RENDER_BROWSERS).apply {
        // From :100 up: the market account browsers hold :92 and :93, and a render browser on one of
        // them took the sign-in pictures and the clicks the user made on them.
        repeat(RENDER_BROWSERS) { i -> trySend(WarmBrowser("render${i + 1}", 100 + i, "render-profile-${i + 1}", "render-browser-${i + 1}.log")) }
    }

    /** [WarmBrowser.get] on whichever browser is free first. */
    suspend fun get(url: String, markers: List<String>, timeoutS: Int): WarmBrowser.Page? {
        val clock = kotlin.coroutines.coroutineContext[CrawlClock]
        val browser = if (clock != null) clock.waitingOnOthers { pool.receive() } else pool.receive()
        try {
            return browser.get(url, markers, timeoutS)
        } finally {
            pool.send(browser)
        }
    }

    private const val RENDER_BROWSERS = 3
}
