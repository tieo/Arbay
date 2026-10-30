package io.github.tieo.arbay.chat

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.crawler.StealthBrowserClient
import io.github.tieo.arbay.crawler.killTree
import io.github.tieo.arbay.model.PlatformId
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/** A request a market's browser answered as failed; [detail] is the page it failed on, when it sent one. */
class ChatFailure(message: String, val signedOut: Boolean, val detail: JsonObject? = null) : RuntimeException(message)

/**
 * The Chrome that holds the user's session on one market, kept running by that market's sidecar
 * script.
 *
 * One browser per market for the life of the server, each on a display of its own so the pictures a
 * sign-in shows are of that browser and of no other. Its profile sits in the data directory, so the
 * session survives restarts and redeploys. Requests go one at a time: the sidecar drives one page.
 */
open class MarketSidecar(
    val market: PlatformId,
    private val script: String,
    private val display: Int,
    private val profileEnv: String,
    private val profileDir: String,
    private val logName: String,
    private val vncPort: Int,
) {
    private val log = LoggerFactory.getLogger("${MarketSidecar::class.java.name}.${market.name}")
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    private var xvfb: Process? = null
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null

    /** Ask the sidecar for [op] and wait at most [timeoutMs] for its answer. A page call that never
     *  answered once held this lock, and every request behind it, with the app left waiting on the
     *  server for a minute at a time; the sidecar is ended instead. */
    suspend fun call(op: String, timeoutMs: Long = 60_000, vararg args: Pair<String, Any?>): JsonObject = lock.withLock {
        // The sidecar gives up a little before this side does, and answers that it did.
        val request = JsonObject(mapOf("op" to JsonPrimitive(op), "timeout" to JsonPrimitive(timeoutMs / 1000.0 - 5)) + args.associate { (k, v) ->
            k to when (v) {
                null -> JsonPrimitive(null as String?)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is JsonElement -> v
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
            log.warn("{} browser did not answer {}: {}", market.displayName, op, e.message)
            stop()
            throw ChatFailure("Arbay's browser for ${market.displayName} did not answer", signedOut = false)
        }
        if (answer["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            // A sidecar that ran out of time on a request is stuck on its page; the next starts afresh.
            if (answer["stuck"]?.jsonPrimitive?.booleanOrNull == true) stop()
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
        val reply = output!!.readLine() ?: throw IllegalStateException("$script exited")
        return json.parseToJsonElement(reply).jsonObject
    }

    private fun ensureRunning() {
        if (process?.isAlive == true) return
        ensureXvfb()
        val started = ProcessBuilder("python3", File(StealthBrowserClient.scriptDir, script).absolutePath)
            .directory(StealthBrowserClient.scriptDir)
            .redirectError(ProcessBuilder.Redirect.appendTo(DataDir.file("logs/$logName").apply { parentFile.mkdirs() }))
            .also {
                it.environment()["DISPLAY"] = ":$display"
                it.environment()[profileEnv] = DataDir.file(profileDir).absolutePath
                // The crawlers' captcha viewer has 5900; a market's browser never shares a VNC server with it.
                it.environment()["ARBAY_VNC_PORT"] = vncPort.toString()
            }
            .start()
        process = started
        input = started.outputStream.bufferedWriter()
        output = started.inputStream.bufferedReader()
        val ready = output!!.readLine() ?: run {
            stop()
            throw IllegalStateException("$script exited on start; see logs/$logName")
        }
        log.info("{} browser started: {}", market.displayName, ready)
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
            // A sidecar that answers closes its browser, and with it the signed in profile, on
            // its own; one stuck on a page call that never returns is ended.
            if (!p.waitFor(5, TimeUnit.SECONDS)) killTree(p)
        }
        process = null
        input = null
        output = null
    }
}

/** The user's Kleinanzeigen session, kept by kleinanzeigen_chat.py. */
object ChatBrowser : MarketSidecar(PlatformId.KLEINANZEIGEN, "kleinanzeigen_chat.py", 97, "ARBAY_CHAT_PROFILE", "chat-profile", "chat-browser.log", 5901)

/** The user's eBay session, for every eBay site, kept by ebay_chat.py; apart from the crawler's Chrome. */
object EbayChatBrowser : MarketSidecar(PlatformId.EBAY_DE, "ebay_chat.py", 93, "ARBAY_EBAY_CHAT_PROFILE", "ebay-chat-profile", "ebay-chat-browser.log", 5902)

/** The user's willhaben session, kept by willhaben_chat.py. */
object WillhabenChatBrowser : MarketSidecar(PlatformId.WILLHABEN, "willhaben_chat.py", 92, "ARBAY_WILLHABEN_CHAT_PROFILE", "willhaben-chat-profile", "willhaben-chat-browser.log", 5903)

/** The user's Vinted session, kept by vinted_chat.py. */
object VintedChatBrowser : MarketSidecar(PlatformId.VINTED_DE, "vinted_chat.py", 95, "ARBAY_VINTED_CHAT_PROFILE", "vinted-chat-profile", "vinted-chat-browser.log", 5904)
