package io.github.tieo.arbay.chat

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.crawler.StealthBrowserClient
import io.github.tieo.arbay.crawler.killTree
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * The Chrome that holds the user's Kleinanzeigen session, kept running by kleinanzeigen_chat.py.
 *
 * One browser for the life of the server, on a display of its own (:97) so signing in over noVNC
 * shows this browser and not a crawler's. Its profile sits in the data directory, so the session
 * survives restarts and redeploys. Requests go one at a time: the sidecar drives a single page.
 */
object ChatBrowser {
    private val log = LoggerFactory.getLogger(ChatBrowser::class.java)
    private const val DISPLAY = ":97"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private var xvfb: Process? = null
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null

    /** A request the sidecar answered as failed; [detail] is the page it failed on, when it sent one. */
    class ChatFailure(message: String, val signedOut: Boolean, val detail: JsonObject? = null) : RuntimeException(message)

    /** Ask the sidecar for [op] and wait at most [timeoutMs] for its answer. */
    suspend fun call(op: String, timeoutMs: Long = 60_000, vararg args: Pair<String, Any?>): JsonObject = lock.withLock {
        // The sidecar gives up a little before this side does, and answers that it did.
        val request = JsonObject(mapOf("op" to JsonPrimitive(op), "timeout" to JsonPrimitive(timeoutMs / 1000.0 - 5)) + args.associate { (k, v) ->
            k to when (v) {
                null -> JsonPrimitive(null as String?)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                else -> JsonPrimitive(v.toString())
            }
        })
        val answer = try {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    // A blocked read cannot be cancelled; ending the sidecar ends the read.
                    val watchdog = launch { delay(timeoutMs); process?.let(::killTree) }
                    try { exchange(request.toString()) } finally { watchdog.cancel() }
                }
            }
        } catch (e: Exception) {
            // A sidecar that missed its answer is out of step with the requests; start over.
            log.warn("Chat browser did not answer {}: {}", op, e.message)
            stop()
            throw ChatFailure("Arbay's browser did not answer", signedOut = false)
        }
        if (answer["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw ChatFailure(
                answer["error"]?.jsonPrimitive?.contentOrNull ?: "failed",
                signedOut = answer["signedOut"]?.jsonPrimitive?.booleanOrNull == true,
                detail = answer["detail"] as? JsonObject,
            )
        }
        answer
    }

    private fun exchange(line: String): JsonObject {
        ensureRunning()
        input!!.apply { write(line); newLine(); flush() }
        val reply = output!!.readLine() ?: throw IllegalStateException("chat sidecar exited")
        return json.parseToJsonElement(reply).jsonObject
    }

    private fun ensureRunning() {
        if (process?.isAlive == true) return
        ensureXvfb()
        val script = File(StealthBrowserClient.scriptDir, "kleinanzeigen_chat.py")
        val started = ProcessBuilder("python3", script.absolutePath)
            .directory(StealthBrowserClient.scriptDir)
            .redirectError(ProcessBuilder.Redirect.appendTo(DataDir.file("logs/chat-browser.log").apply { parentFile.mkdirs() }))
            .also {
                it.environment()["DISPLAY"] = DISPLAY
                it.environment()["ARBAY_CHAT_PROFILE"] = DataDir.file("chat-profile").absolutePath
                // The crawlers' captcha viewer has 5900; this one never shares a VNC server with it.
                it.environment()["ARBAY_VNC_PORT"] = "5901"
            }
            .start()
        process = started
        input = started.outputStream.bufferedWriter()
        output = started.inputStream.bufferedReader()
        val ready = output!!.readLine() ?: run {
            stop()
            throw IllegalStateException("chat sidecar exited on start; see logs/chat-browser.log")
        }
        log.info("Chat browser started: {}", ready)
    }

    private fun ensureXvfb() {
        if (xvfb?.isAlive == true) return
        File("/tmp/.X97-lock").delete()
        File("/tmp/.X11-unix/X97").delete()
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
