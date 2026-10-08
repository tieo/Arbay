package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.PlatformId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * How often each Kleinanzeigen ad has been looked at, as the ad page's own counter says.
 *
 * An ad seen thousands of times over two months and still for sale at a low price is one many
 * buyers looked at and none bought, which is what a scam looks like from outside. The counter is
 * the page's own call (s-vac-inc-get.json), asked one ad at a time with a pause between, and kept
 * for six hours, since a count that old still tells the story.
 */
object ViewCounts {
    private val log = LoggerFactory.getLogger(ViewCounts::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, Pair<Instant, Int>>()
    private val one = Mutex()
    private const val MAX_PER_CALL = 80

    /** Counts for the Kleinanzeigen listings among [listingIds]; others and failures are left out. */
    suspend fun of(listingIds: List<String>): Map<String, Int> {
        val now = Clock.System.now()
        val wanted = listingIds.filter { it.startsWith("${PlatformId.KLEINANZEIGEN.name}:") }.distinct().take(MAX_PER_CALL)
        val out = mutableMapOf<String, Int>()
        for (id in wanted) {
            cache[id]?.takeIf { now - it.first < 6.hours }?.let { out[id] = it.second; continue }
            val count = one.withLock { fetch(id.substringAfter(':')).also { delay(250) } } ?: continue
            cache[id] = now to count
            out[id] = count
        }
        return out
    }

    private suspend fun fetch(adId: String): Int? = try {
        val r = CrawlerRegistry.httpClient.get("https://www.kleinanzeigen.de/s-vac-inc-get.json?adId=$adId") {
            header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            header("Accept", "application/json")
        }
        if (r.status.isSuccess()) json.parseToJsonElement(r.bodyAsText()).jsonObject["numVisits"]?.jsonPrimitive?.int else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug("No view count for {}: {}", adId, e.message)
        null
    }
}
