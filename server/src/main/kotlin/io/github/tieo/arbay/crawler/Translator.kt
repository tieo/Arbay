package io.github.tieo.arbay.crawler

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates a short search term between languages so a cross-border search can query each market
 * in its own language ("Parkettschleifmaschine" → IT "levigatrice per parquet"). Uses Google's
 * keyless gtx endpoint (no API key, fine for the low volume of user search terms); every result is
 * cached forever, so a given term is translated once per target language. On any failure it returns
 * the input unchanged — a missing translation degrades to searching the original term, never breaks.
 */
object Translator {

    private val log = LoggerFactory.getLogger(Translator::class.java)
    private val client = HttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, String>()

    /** Translate [text] from [from] to [to] (ISO-639-1 codes). Same language or blank → unchanged. */
    suspend fun translate(text: String, from: String, to: String): String {
        if (text.isBlank() || from.equals(to, ignoreCase = true)) return text
        val key = "$from|$to|${text.lowercase().trim()}"
        cache[key]?.let { return it }
        val translated = runCatching {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=$from&tl=$to&dt=t&q=${text.encodeURLQueryComponent()}"
            parseFirstSegment(client.get(url).bodyAsText())
        }.getOrElse {
            log.debug("translate '{}' {}->{} failed: {}", text, from, to, it.message)
            null
        } ?: text
        cache[key] = translated
        return translated
    }

    /** The gtx response is nested arrays: [[["translated","original",…],…],…]. Concatenate the
     *  translated chunks in the first element. */
    internal fun parseFirstSegment(body: String): String? {
        val root = json.parseToJsonElement(body).jsonArray
        val segments = root.getOrNull(0)?.jsonArray ?: return null
        val out = buildString {
            for (seg in segments) {
                seg.jsonArray.getOrNull(0)?.jsonPrimitive?.content?.let { append(it) }
            }
        }
        return out.takeIf { it.isNotBlank() }
    }
}
