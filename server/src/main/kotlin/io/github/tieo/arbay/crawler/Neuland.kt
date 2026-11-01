package io.github.tieo.arbay.crawler

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Neuland, the service every website is read through once Arbay is its client: its named browsers
 * read a page past whatever stands before it (consent screens, bot checks, captchas), which it
 * recognises on the page itself. Used where NEULAND_URL names the service; until then Arbay's own
 * browser helpers read the pages.
 */
object Neuland {
    private val log = LoggerFactory.getLogger(Neuland::class.java)
    private val base = System.getenv("NEULAND_URL")?.trimEnd('/')?.takeIf { it.isNotBlank() }
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) { install(HttpTimeout) }

    val enabled: Boolean get() = base != null

    /**
     * Reads [url] in Neuland's browser [browser] until one of [content] shows, the site's error page
     * ([errors]) does, a refusal stands, or [timeoutS] passes. Null where Neuland did not answer.
     */
    suspend fun read(browser: String, url: String, content: List<String>, errors: List<String>, refused: List<String>, timeoutS: Int): WarmBrowser.Page? {
        val body = JsonObject(mapOf(
            "url" to JsonPrimitive(url),
            "content" to JsonArray(content.map(::JsonPrimitive)),
            "errors" to JsonArray(errors.map(::JsonPrimitive)),
            "refused" to JsonArray(refused.map(::JsonPrimitive)),
            "timeoutS" to JsonPrimitive(timeoutS),
        ))
        val answer = call("browsers/$browser/read", body, timeoutS + 30L) ?: return null
        return WarmBrowser.Page(
            html = answer["html"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            url = answer["url"]?.jsonPrimitive?.contentOrNull ?: url,
            page = answer["state"]?.jsonPrimitive?.contentOrNull ?: "unknown",
        )
    }

    /** Where each of a site's redirects [urls] leads in Neuland's browser [browser]. */
    suspend fun resolve(browser: String, urls: List<String>, leaving: String, timeoutS: Int): Map<String, String> {
        val body = JsonObject(mapOf(
            "urls" to JsonArray(urls.map(::JsonPrimitive)),
            "leaving" to JsonPrimitive(leaving),
            "timeoutS" to JsonPrimitive(timeoutS),
        ))
        val answer = call("browsers/$browser/resolve", body, timeoutS.toLong() * urls.size + 30) ?: return emptyMap()
        return (answer["to"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
    }

    /** The cookies of Neuland's browser [browser] for [domain], and its user agent. */
    suspend fun cookies(browser: String, domain: String): Pair<Map<String, String>, String>? = runCatching {
        val response = client.get("$base/browsers/$browser/cookies") {
            parameter("domain", domain)
            timeout { requestTimeoutMillis = 30_000 }
        }
        if (!response.status.isSuccess()) return null
        val answer = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val cookies = (answer["cookies"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
        cookies to (answer["userAgent"]?.jsonPrimitive?.contentOrNull ?: "")
    }.onFailure { log.warn("Neuland cookies of {}: {}", browser, it.message) }.getOrNull()

    private suspend fun call(path: String, body: JsonObject, limitS: Long): JsonObject? = runCatching {
        val response = client.post("$base/$path") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
            timeout { requestTimeoutMillis = limitS * 1000 }
        }
        val answer = json.parseToJsonElement(response.bodyAsText()).jsonObject
        if (!response.status.isSuccess()) {
            log.warn("Neuland {}: {}", path, answer["error"]?.jsonPrimitive?.contentOrNull ?: response.status)
            return null
        }
        answer
    }.onFailure { log.warn("Neuland {}: {}", path, it.message) }.getOrNull()
}
