package io.github.tieo.arbay.neuland

import io.github.tieo.arbay.crawler.CrawlerBlockedException
import io.github.tieo.arbay.crawler.ErrorType
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pages that need no browser, fetched by Neuland's client that greets a site as Chrome does (its
 * TLS handshake and HTTP/2 settings), after the site's front page where a bot defence sets its
 * cookies there, and past Akamai's interstitial.
 */
object NeulandHttp {
    /**
     * The body of [url], arrived at from [cameFrom] (the site's front page) where given, with
     * [headers] beside Chrome's own. A site answering other than 200 is a [CrawlerBlockedException]
     * of that status; Neuland not answering is a network error.
     */
    suspend fun fetch(url: String, cameFrom: String? = null, headers: Map<String, String> = emptyMap()): String {
        val body = JsonObject(buildMap {
            put("url", JsonPrimitive(url))
            cameFrom?.let { put("cameFrom", JsonPrimitive(it)) }
            if (headers.isNotEmpty()) put("headers", JsonObject(headers.mapValues { JsonPrimitive(it.value) }))
        })
        val answer = try {
            Neuland.request(HttpMethod.Post, "fetch", body, limitS = 120).jsonObject
        } catch (e: Neuland.Refusal) {
            throw CrawlerBlockedException("$url: ${e.message}", ErrorType.NETWORK_ERROR)
        }
        val status = answer["status"]?.jsonPrimitive?.intOrNull ?: 0
        if (status != 200) {
            throw CrawlerBlockedException(
                "$url: HTTP $status",
                when (status) {
                    403 -> ErrorType.BLOCKED_403
                    429 -> ErrorType.RATE_LIMITED_429
                    503 -> ErrorType.SERVICE_UNAVAILABLE_503
                    404, 410 -> ErrorType.NOT_FOUND_404
                    else -> ErrorType.UNKNOWN
                },
                statusCode = status,
            )
        }
        return answer["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}
