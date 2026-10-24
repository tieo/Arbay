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
import kotlinx.coroutines.sync.withLock
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
 * The Chrome every eBay page is read through, kept running by ebay_browser.py.
 *
 * eBay's edge serves its error page to every client that does not look like a person's browser,
 * and a freshly started Chrome is one of them; a Chrome that keeps its profile and reads one page
 * after another is not. So there is one, for the life of the server, on a display of its own (:96),
 * its profile in the data directory so its cookies outlive restarts. Its pages are read one at a
 * time, which is also the pace eBay lets through.
 */
object EbayBrowser {
    private val log = LoggerFactory.getLogger(EbayBrowser::class.java)
    private const val DISPLAY = ":96"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private var xvfb: Process? = null
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null

    /** A page as the browser read it: [page] says whether it is the page asked for ("content"), eBay's
     *  error page, its challenge, or none of them. */
    class Page(val html: String, val url: String, val page: String)

    /**
     * Reads [url] and waits until one of [markers] shows in it, eBay's error page shows, or [timeoutS]
     * passes. Null where the browser could not be started or did not answer, which leaves the
     * caller its other ways in.
     */
    suspend fun get(url: String, markers: List<String>, timeoutS: Int = 30): Page? = lock.withLock {
        val request = JsonObject(mapOf(
            "op" to JsonPrimitive("get"), "url" to JsonPrimitive(url), "timeout" to JsonPrimitive(timeoutS),
            "markers" to JsonArray(markers.map(::JsonPrimitive)),
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
            log.warn("eBay browser did not answer for {}: {}", url, e.message)
            stop()
            return@withLock null
        }
        if (answer["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            log.warn("eBay browser failed {}: {}", url, answer["error"]?.jsonPrimitive?.contentOrNull)
            if (answer["stuck"]?.jsonPrimitive?.booleanOrNull == true) stop()
            return@withLock null
        }
        Page(
            html = answer["html"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            url = answer["url"]?.jsonPrimitive?.contentOrNull ?: url,
            page = answer["page"]?.jsonPrimitive?.contentOrNull ?: "unknown",
        )
    }

    private fun exchange(line: String): JsonObject {
        ensureRunning()
        input!!.apply { write(line); newLine(); flush() }
        val reply = output!!.readLine() ?: throw IllegalStateException("eBay browser exited")
        return json.parseToJsonElement(reply).jsonObject
    }

    private fun ensureRunning() {
        if (process?.isAlive == true) return
        ensureXvfb()
        val script = File(StealthBrowserClient.scriptDir, "ebay_browser.py")
        val started = ProcessBuilder("python3", script.absolutePath)
            .directory(StealthBrowserClient.scriptDir)
            .redirectError(ProcessBuilder.Redirect.appendTo(DataDir.file("logs/ebay-browser.log").apply { parentFile.mkdirs() }))
            .also {
                it.environment()["DISPLAY"] = DISPLAY
                it.environment()["ARBAY_EBAY_PROFILE"] = DataDir.file("ebay-profile").absolutePath
            }
            .start()
        process = started
        input = started.outputStream.bufferedWriter()
        output = started.inputStream.bufferedReader()
        val ready = output!!.readLine() ?: run {
            stop()
            throw IllegalStateException("eBay browser exited on start; see logs/ebay-browser.log")
        }
        log.info("eBay browser started: {}", ready)
    }

    private fun ensureXvfb() {
        if (xvfb?.isAlive == true) return
        File("/tmp/.X96-lock").delete()
        File("/tmp/.X11-unix/X96").delete()
        xvfb = ProcessBuilder("Xvfb", DISPLAY, "-screen", "0", "1280x900x24", "-nolisten", "tcp")
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
