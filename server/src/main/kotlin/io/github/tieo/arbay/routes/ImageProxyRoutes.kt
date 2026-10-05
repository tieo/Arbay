package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.plugins.NotFoundException
import io.github.tieo.arbay.repo.ListingArchive
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A market's photo, fetched by the server for a browser.
 *
 * A browser may only read an image fetched from script when the image's host allows it, and the
 * markets' image hosts do not; the web app draws every image through script. The phone app reads
 * the same images directly and never comes here.
 *
 * The address comes from the caller, so only public http(s) hosts are fetched, never this
 * machine or its network, and only an answer whose bytes are an image is passed on.
 */
fun Route.imageProxyRoutes() {
    get("/api/image") {
        var address = call.request.queryParameters["url"] ?: throw NotFoundException("Missing url")
        // Redirects are followed here, one checked hop at a time: a public host answering with a
        // redirect to this machine is the same request as asking for this machine directly.
        repeat(MAX_HOPS) {
            if (!isPublicAddress(address)) {
                call.respondText("Not an address of a public image", status = HttpStatusCode.BadRequest)
                return@get
            }
            val response = noRedirects.get(address)
            val next = response.headers[HttpHeaders.Location]
            if (response.status.value in 300..399 && next != null) {
                address = URI(address).resolve(next).toString()
                return@repeat
            }
            val bytes: ByteArray = response.body()
            if (!response.status.isSuccess() || !ListingArchive.looksLikeImage(bytes)) {
                call.respondText("The market sent no image", status = HttpStatusCode.BadGateway)
                return@get
            }
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondBytes(bytes, response.contentType()?.takeIf { it.contentType == "image" } ?: ContentType.Image.Any)
            return@get
        }
        call.respondText("Too many redirects", status = HttpStatusCode.BadGateway)
    }
}

private const val MAX_HOPS = 4

private val noRedirects by lazy { CrawlerRegistry.httpClient.config { followRedirects = false } }

private suspend fun isPublicAddress(address: String): Boolean {
    val uri = runCatching { URI(address) }.getOrNull() ?: return false
    val host = uri.host ?: return false
    return uri.scheme in setOf("http", "https") && isPublic(host)
}

/** Every address the host resolves to is on the public internet. */
private suspend fun isPublic(host: String): Boolean = withContext(Dispatchers.IO) {
    val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
    addresses.isNotEmpty() && addresses.all { isOnPublicInternet(it) }
}

/** Not this machine, not a private or link-local network, not multicast. */
internal fun isOnPublicInternet(address: InetAddress): Boolean =
    !(address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
        address.isAnyLocalAddress || address.isMulticastAddress ||
        // Unique local IPv6 (fc00::/7), which Java does not count as site-local.
        (address.address.size == 16 && (address.address[0].toInt() and 0xFE) == 0xFC))
