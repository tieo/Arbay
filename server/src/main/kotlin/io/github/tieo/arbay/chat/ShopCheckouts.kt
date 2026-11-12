package io.github.tieo.arbay.chat

import io.github.tieo.arbay.neuland.Neuland
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * The web shops Neuland checks out at, asked per product address. Whether a shop has a checkout at
 * all is a matter of its software (Shopify), so a shop without one is remembered by host for a day;
 * at a shop with one, whether an address is a product it can buy is remembered per address. An
 * answer that did not come stands for ten minutes, so a Neuland that is down slows no search after
 * the first.
 */
object ShopCheckouts {
    private val log = LoggerFactory.getLogger(ShopCheckouts::class.java)

    private data class Known(val site: String?, val at: Long)

    /** Hosts whose shop has no checkout Neuland runs, or that did not answer. */
    private val noCheckout = ConcurrentHashMap<String, Long>()
    private val byUrl = ConcurrentHashMap<String, Known>()

    private const val KEEP_MS = 24 * 3_600_000L
    private const val UNANSWERED_MS = 10 * 60_000L

    /** How long a search waits for Neuland's answers before its offers go out without a checkout. */
    private const val ASK_MS = 15_000L

    /** Neuland reads a shop's product data per question; a search with sixty web offers asks a few at a time. */
    private val asking = kotlinx.coroutines.sync.Semaphore(6)

    /** The checkout Neuland names for the product at [url], or null where it checks out at none or did
     *  not answer; [fresh] asks Neuland whatever it answered before, as a purchase does. */
    suspend fun siteOf(url: String, fresh: Boolean = false): String? {
        val host = hostOf(url) ?: return null
        val now = System.currentTimeMillis()
        if (!fresh) {
            noCheckout[host]?.takeIf { it > now }?.let { return null }
            byUrl[url]?.takeIf { now - it.at < KEEP_MS }?.let { return it.site }
        }
        val answer = try {
            Neuland.request(io.ktor.http.HttpMethod.Get, "checkout?url=${URLEncoder.encode(url, Charsets.UTF_8)}", limitS = 30) as? JsonObject
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A search that stopped waiting learned nothing about the shop.
            throw e
        } catch (e: Exception) {
            log.debug("Checkout of {} not asked: {}", host, e.message)
            null
        }
        if (answer == null || "checkout" !in answer) {
            noCheckout[host] = now + UNANSWERED_MS
            return null
        }
        sweep(now)
        if (answer["checkout"] is kotlinx.serialization.json.JsonNull) {
            noCheckout[host] = now + KEEP_MS
            return null
        }
        noCheckout.remove(host)
        val site = answer["site"]?.jsonPrimitive?.contentOrNull?.takeIf { answer["canCheckOut"]?.jsonPrimitive?.booleanOrNull == true }
        byUrl[url] = Known(site, now)
        return site
    }

    /** [listings] with the checkout of each offer a web shop's checkout can buy. */
    suspend fun withCheckouts(listings: List<io.github.tieo.arbay.model.Listing>): List<io.github.tieo.arbay.model.Listing> {
        if (listings.isEmpty()) return listings
        val sites = coroutineScope {
            listings.distinctBy { it.url }.map { l -> async { l.url to withTimeoutOrNull(ASK_MS) { asking.withPermit { siteOf(l.url) } } } }.awaitAll().toMap()
        }
        return listings.map { l -> sites[l.url]?.let { l.copy(checkout = it) } ?: l }
    }

    /** Drops what has run out, so a server crawling for months keeps a day's answers. */
    private fun sweep(now: Long) {
        if (byUrl.size < 2_000) return
        byUrl.entries.removeIf { now - it.value.at >= KEEP_MS }
        noCheckout.entries.removeIf { it.value <= now }
    }

    /** The host of [url] as Neuland names a shop, without "www."; null for anything but https. */
    fun hostOf(url: String): String? =
        runCatching { java.net.URI(url) }.getOrNull()?.takeIf { it.scheme == "https" }?.host?.lowercase()?.removePrefix("www.")
}
