package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Pushes a message to the user's phone through ntfy.sh, for what is worth an interruption when no
 * app is open: an offer that fits a saved search well (see [FitScore]).
 *
 * The topic is the address and the only secret: anyone who knows it can read what is sent. It is
 * kept in the server's own data directory (`ntfy.json`, `{"topic": "…"}`) rather than in code or
 * deployment config, and nothing is sent while there is none. Published as JSON to ntfy's root
 * URL, which carries umlauts and the euro sign in the title where an HTTP header would not.
 */
object Ntfy {
    private val log = LoggerFactory.getLogger(Ntfy::class.java)
    // Defaults are written out (a button needs its "action": "view"), absent values are not.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Serializable
    private data class Settings(val topic: String? = null, val server: String = "https://ntfy.sh")

    @Serializable
    private data class Message(
        val topic: String,
        val title: String,
        val message: String,
        val click: String? = null,
        val tags: List<String> = emptyList(),
        val priority: Int = 3,
        val actions: List<Action> = emptyList(),
    )

    /** A button under the message that opens a link: ntfy shows up to three. */
    @Serializable
    data class Action(val label: String, val url: String, val action: String = "view")

    private fun settings(): Settings? = runCatching {
        DataDir.file("ntfy.json").takeIf { it.exists() }?.readText()?.let { json.decodeFromString<Settings>(it) }
    }.getOrNull()

    val configured: Boolean get() = settings()?.topic?.isNotBlank() == true

    /** Sends one message; false when no topic is set or ntfy did not take it. */
    suspend fun send(
        title: String,
        message: String,
        click: String? = null,
        tags: List<String> = emptyList(),
        actions: List<Action> = emptyList(),
    ): Boolean {
        val s = settings() ?: return false
        val topic = s.topic?.takeIf { it.isNotBlank() } ?: return false
        return try {
            val response = CrawlerRegistry.httpClient.post(s.server.trimEnd('/')) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(Message.serializer(), Message(topic, title, message, click, tags, actions = actions.take(3))))
            }
            response.status.isSuccess().also { if (!it) log.warn("ntfy refused a message: {}", response.status) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("ntfy unreachable: {}", e.message)
            false
        }
    }
}
