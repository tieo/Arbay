package io.github.tieo.arbay.routes

import io.github.tieo.arbay.plugins.BadRequestException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * A URL a request asks the server to fetch, accepted only when it points at the public internet.
 *
 * The debug page fetch and the listing detail both load whatever URL they are given, from inside
 * the network the server runs in. Held to http(s) and to hosts that resolve to public addresses,
 * they cannot be pointed at the machine itself or at the other services on its network.
 */
internal fun requirePublicHttpUrl(url: String): String {
    val uri = runCatching { URI(url) }.getOrNull() ?: throw BadRequestException("Not a URL: $url")
    if (uri.scheme?.lowercase() !in setOf("http", "https")) throw BadRequestException("Only http(s) URLs are fetched")
    val host = uri.host ?: throw BadRequestException("URL has no host")
    val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
        ?: throw BadRequestException("Host does not resolve: $host")
    if (addresses.isEmpty() || addresses.any { !isPublic(it) }) {
        throw BadRequestException("Host is not on the public internet: $host")
    }
    return url
}

internal fun isPublic(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false
    val bytes = address.address
    if (address is Inet6Address) {
        // fc00::/7, the unique local range, which isSiteLocalAddress does not cover.
        if (bytes[0].toInt() and 0xFE == 0xFC) return false
    } else {
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        // 100.64.0.0/10 (carrier-grade NAT, also what VPNs hand out) and 0.0.0.0/8.
        if (first == 100 && second in 64..127) return false
        if (first == 0) return false
    }
    return true
}
