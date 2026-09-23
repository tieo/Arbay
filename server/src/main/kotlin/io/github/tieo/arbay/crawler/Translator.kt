package io.github.tieo.arbay.crawler

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLQueryComponent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

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
        // Three sources, tried in turn. Google's keyless endpoint answers a served page ("Sorry…")
        // to anything it takes for automated traffic, and did so from every address here, which is
        // a failure that looks exactly like a term with no translation. A second and third source
        // keep one refusal from silently turning every foreign market back into the home language.
        val translated = sources(text, from, to).firstNotNullOfOrNull { (name, fetch) ->
            // A cancelled search must not fall through to the next source and then cache the
            // untranslated text as this term's answer for the life of the process.
            val answer = try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.debug("translate '{}' {}->{} via {} failed: {}", text, from, to, name, e.message)
                null
            }
            answer
                ?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals(text, ignoreCase = true) }
        } ?: text
        cache[key] = translated
        return translated
    }

    private fun sources(text: String, from: String, to: String): List<Pair<String, suspend () -> String?>> = listOf(
        "google" to {
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx" +
                "&sl=$from&tl=$to&dt=t&q=${text.encodeURLQueryComponent()}"
            parseFirstSegment(client.get(url).bodyAsText())
        },
        "lingva" to {
            val url = "https://lingva.ml/api/v1/$from/$to/${text.encodeURLQueryComponent()}"
            json.parseToJsonElement(client.get(url).bodyAsText())
                .jsonObject["translation"]?.jsonPrimitive?.content
        },
        "mymemory" to {
            val url = "https://api.mymemory.translated.net/get" +
                "?q=${text.encodeURLQueryComponent()}&langpair=$from|$to"
            json.parseToJsonElement(client.get(url).bodyAsText())
                .jsonObject["responseData"]?.jsonObject?.get("translatedText")?.jsonPrimitive?.content
        },
    )

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
