package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * own, its profile in the data directory so its cookies outlive restarts. Its pages are read one at
 * a time, which is also the pace such a site lets through.
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
) {
    private val log = LoggerFactory.getLogger("${WarmBrowser::class.java.name}.$name")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private var xvfb: Process? = null
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null

    /** A page as the browser read it: [page] says whether it is the page asked for ("content"), the
     *  site's error page, a challenge, the site refusing the browser ("refused"), or none of them. */
    class Page(val html: String, val url: String, val page: String)

    /**
     * Reads [url] and waits until one of [markers] shows in it, the site's error page shows, or
     * [timeoutS] passes. Null where the browser could not be started or did not answer, which
     * leaves the caller its other ways in.
     */
    suspend fun get(url: String, markers: List<String>, timeoutS: Int = 30, refusals: List<String> = emptyList()): Page? {
        // One browser serves every search of its site, and a crawl waiting its turn is not its
        // market failing to answer: eBay's five sites share this one, and four waited out their
        // whole time limit behind the fifth.
        val clock = kotlin.coroutines.coroutineContext[CrawlClock]
        if (clock != null) clock.waitingOnOthers { lock.lock() } else lock.lock()
        try {
            return read(url, markers, timeoutS, refusals)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun read(url: String, markers: List<String>, timeoutS: Int, refusals: List<String>): Page? {
        val request = JsonObject(mapOf(
            "op" to JsonPrimitive("get"), "url" to JsonPrimitive(url), "timeout" to JsonPrimitive(timeoutS),
            "markers" to JsonArray(markers.map(::JsonPrimitive)),
            "errors" to JsonArray(errors.map(::JsonPrimitive)),
            "accept" to JsonArray(accept.map(::JsonPrimitive)),
            "refusals" to JsonArray(refusals.map(::JsonPrimitive)),
        ))
        val answer = try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    // A blocked read cannot be cancelled; ending the sidecar ends the read.
                    val watchdog = launch { delay((timeoutS + 30) * 1000L); process?.let(::killTree) }
                    try { exchange(request.toString()) } finally { watchdog.cancel() }
                }
            }
        } catch (e: Exception) {
            log.warn("{} browser did not answer for {}: {}", name, url, e.message)
            stop()
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

    private fun exchange(line: String): JsonObject {
        ensureRunning()
        input!!.apply { write(line); newLine(); flush() }
        val reply = output!!.readLine() ?: throw IllegalStateException("$name browser exited")
        return json.parseToJsonElement(reply).jsonObject
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
            }
            .start()
        process = started
        input = started.outputStream.bufferedWriter()
        output = started.inputStream.bufferedReader()
        val ready = output!!.readLine() ?: run {
            stop()
            throw IllegalStateException("$name browser exited on start; see logs/$logName")
        }
        log.info("{} browser started: {}", name, ready)
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
        process?.let { p ->
            runCatching { input?.close() }
            if (!p.waitFor(5, TimeUnit.SECONDS)) killTree(p)
        }
        process = null
        input = null
        output = null
    }
}

/**
 * The Chrome every eBay page is read through. eBay's edge serves its error page to every client
 * that does not look like a person's browser, and a freshly started Chrome is one of them; a Chrome
 * that keeps its profile and reads one page after another is not.
 */
object EbayBrowser : WarmBrowser("eBay", 96, "ebay-profile", "ebay-browser.log", errors = listOf("went wrong"))

/**
 * The Chrome the search engines that refuse a plain request are asked through. Google has answered
 * only browsers that run its scripts since 2025, and SearXNG's Google and Qwant got "access denied"
 * from the server for that, while a person's Chrome on the same address searches normally. Its
 * consent screen is accepted once and remembered in the profile.
 */
object SearchBrowser : WarmBrowser(
    "search", 91, "search-profile", "search-browser.log",
    accept = listOf("Alle akzeptieren", "Accept all", "Akzeptieren", "Tout accepter", "Accetta tutto"),
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
