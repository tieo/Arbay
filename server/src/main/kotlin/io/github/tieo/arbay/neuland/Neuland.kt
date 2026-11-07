package io.github.tieo.arbay.neuland

import io.github.tieo.arbay.markets.CrawlerBlockedException
import io.github.tieo.arbay.markets.ErrorType

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
import io.ktor.http.HttpMethod
import io.ktor.client.statement.readRawBytes
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
 * into offers. NEULAND_URL names the service.
 */
object Neuland {
    private val log = LoggerFactory.getLogger(Neuland::class.java)
    private val base = System.getenv("NEULAND_URL")?.trimEnd('/')?.takeIf { it.isNotBlank() }
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) { install(HttpTimeout) }

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

    /** Bytes Neuland answers at [path] (a tab's picture); a [Refusal] where it answers an error. */
    suspend fun bytes(path: String, limitS: Long = 60): ByteArray {
        val base = base ?: throw Refusal(0, "Neuland is not set up (NEULAND_URL)", JsonObject(emptyMap()))
        val response = runCatching { client.get("$base/$path") { timeout { requestTimeoutMillis = limitS * 1000 } } }
            .getOrElse { throw Refusal(0, "Neuland did not answer: ${it.message}", JsonObject(emptyMap())) }
        if (response.status.isSuccess()) return response.readRawBytes()
        val text = response.bodyAsText()
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        throw Refusal(response.status.value, o["error"]?.jsonPrimitive?.contentOrNull ?: response.status.toString(), o)
    }

    /** Bytes posted to Neuland at [path] as they came (a file for a form), its answer handed back as it went. */
    suspend fun passOnBytes(path: String, bytes: ByteArray): Pair<Int, String>? {
        val base = base ?: return null
        return runCatching {
            val response = client.request("$base/$path") {
                this.method = HttpMethod.Post
                contentType(ContentType.Application.OctetStream)
                setBody(bytes)
                timeout { requestTimeoutMillis = 120_000 }
            }
            response.status.value to response.bodyAsText()
        }.onFailure { log.warn("Neuland {}: {}", path, it.message) }.getOrNull()
    }

    /**
     * A JSON call to Neuland (its account routes, a page fetched without a browser): the status it
     * answered and its JSON. A Neuland that is not set up or did not answer is a [Refusal] of status 0.
     */
    suspend fun request(method: io.ktor.http.HttpMethod, path: String, body: kotlinx.serialization.json.JsonElement? = null, limitS: Long = 120): kotlinx.serialization.json.JsonElement {
        val base = base ?: throw Refusal(0, "Neuland is not set up (NEULAND_URL)", JsonObject(emptyMap()))
        val response = runCatching {
            client.request("$base/$path") {
                this.method = method
                if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
                timeout { requestTimeoutMillis = limitS * 1000 }
            }
        }.getOrElse { throw Refusal(0, "Neuland did not answer: ${it.message}", JsonObject(emptyMap())) }
        val text = response.bodyAsText()
        val answer = runCatching { json.parseToJsonElement(text) }.getOrElse { JsonObject(mapOf("error" to JsonPrimitive(text.take(200)))) }
        if (response.status.isSuccess()) return answer
        val o = answer as? JsonObject ?: JsonObject(emptyMap())
        throw Refusal(response.status.value, o["error"]?.jsonPrimitive?.contentOrNull ?: response.status.toString(), o)
    }

    /** Neuland answering an account route with an error: its status, its words, and the whole answer
     *  (`signedOut`, `declined`, `form`). */
    class Refusal(val status: Int, message: String, val answer: JsonObject) : Exception(message) {
        val signedOut: Boolean get() = answer["signedOut"]?.jsonPrimitive?.contentOrNull == "true"
        val declined: Boolean get() = answer["declined"]?.jsonPrimitive?.contentOrNull == "true"
    }
}
