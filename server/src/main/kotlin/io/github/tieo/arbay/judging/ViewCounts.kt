package io.github.tieo.arbay.judging

import io.github.tieo.arbay.model.PlatformId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import io.github.tieo.arbay.markets.CrawlerRegistry

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

    // The counts are asked for one ad at a time, so a list of seventy fresh ads takes longer than
    // an MCP call may: a search listing them timed out with nothing at all. The asking goes on in
    // the background, and a caller waits at most [wait] for it; the counts that arrive later are
    // cached for the next call.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val asking = ConcurrentHashMap.newKeySet<String>()

    /** Counts for the Kleinanzeigen listings among [listingIds]; others, failures and the ones
     *  still being asked after [wait] are left out. */
    suspend fun of(listingIds: List<String>, wait: Duration = 10.seconds): Map<String, Int> {
        val now = Clock.System.now()
        val wanted = listingIds.filter { it.startsWith("${PlatformId.KLEINANZEIGEN.name}:") }.distinct().take(MAX_PER_CALL)
        fun fresh(id: String) = cache[id]?.takeIf { now - it.first < 6.hours }?.second
        val missing = wanted.filter { fresh(it) == null && asking.add(it) }
        if (missing.isNotEmpty()) {
            val job = scope.launch {
                for (id in missing) {
                    try {
                        val count = one.withLock { fetch(id.substringAfter(':')).also { delay(250) } } ?: continue
                        cache[id] = Clock.System.now() to count
                    } finally {
                        asking.remove(id)
                    }
                }
            }
            withTimeoutOrNull(wait) { job.join() }
        }
        return wanted.mapNotNull { id -> fresh(id)?.let { id to it } }.toMap()
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
