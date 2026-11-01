package io.github.tieo.arbay.crawler

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.request
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
 * recognises on the page itself, and its offers readers read shops, markets and price comparisons
 * into offers. Its browsers are used where NEULAND_URL names the service, Arbay's own browser helpers
 * otherwise; the markets moved to its shop readers need it.
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

    /**
     * What Neuland's offers readers answer at [path]: a site's search ("offers/search?site=&q=") or
     * an offer's sellers ("offers/sellers?url="). A site that refused Neuland is a
     * [CrawlerBlockedException] of the status it answered; Neuland not answering at all is a network
     * error.
     */
    suspend fun offers(path: String, limitS: Long = 120): String {
        val base = base ?: throw CrawlerBlockedException("Neuland is not set up (NEULAND_URL)", ErrorType.NETWORK_ERROR)
        val response = runCatching { client.get("$base/$path") { timeout { requestTimeoutMillis = limitS * 1000 } } }
            .getOrElse { throw CrawlerBlockedException("Neuland did not answer: ${it.message}", ErrorType.NETWORK_ERROR) }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) return body
        val answer = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val status = answer?.get("status")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val error = answer?.get("error")?.jsonPrimitive?.contentOrNull ?: response.status.toString()
        throw CrawlerBlockedException(
            "Neuland: $error",
            when (status) {
                403 -> ErrorType.BLOCKED_403
                429 -> ErrorType.RATE_LIMITED_429
                503 -> ErrorType.SERVICE_UNAVAILABLE_503
                404, 410 -> ErrorType.NOT_FOUND_404
                null -> ErrorType.PARSE_ERROR
                else -> ErrorType.NETWORK_ERROR
            },
            statusCode = status,
        )
    }

    /**
     * A request passed on to Neuland as it came, its answer handed back as it went: the account
     * sign-ins, whose passwords and codes are sealed to Neuland's key and stay sealed here.
     * Null where Neuland is not set up or did not answer.
     */
    suspend fun passOn(method: io.ktor.http.HttpMethod, path: String, body: String?): Pair<Int, String>? {
        val base = base ?: return null
        return runCatching {
            val response = client.request("$base/$path") {
                this.method = method
                if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
                timeout { requestTimeoutMillis = 30_000 }
            }
            response.status.value to response.bodyAsText()
        }.onFailure { log.warn("Neuland {}: {}", path, it.message) }.getOrNull()
    }

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
