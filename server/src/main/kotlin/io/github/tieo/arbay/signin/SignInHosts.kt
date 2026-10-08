package io.github.tieo.arbay.signin

/** The Kleinanzeigen hosts a sign-in may reach through the proxy, and the proxy's address for them. */
object SignInHosts {
    /** The only hosts the proxy fetches; a request for any other host is refused, never forwarded. */
    val allowed: Set<String> = setOf("login.kleinanzeigen.de", "www.kleinanzeigen.de")

    /** The domain every allowed host belongs to; a cookie for any other domain is never kept. */
    const val DOMAIN = "kleinanzeigen.de"

    /** Where the proxy's addresses start: /signin-proxy/{host}/{the path on that host}. */
    const val PREFIX = "/signin-proxy/"

    /** Where a sign-in starts: the site's own sign-in page, which sends the browser on to the login host. */
    const val START = "/signin-proxy/www.kleinanzeigen.de/m-einloggen.html"

    fun allows(host: String): Boolean = host in allowed
}

/** A request to the proxy, read as the Kleinanzeigen address it stands for. */
data class ProxyTarget(val host: String, val pathAndQuery: String) {
    val path: String get() = pathAndQuery.substringBefore('?')
}

/** The target of a proxy request line, or null when its host is not an allowed one. */
fun parseProxyTarget(uri: String): ProxyTarget? {
    if (!uri.startsWith(SignInHosts.PREFIX)) return null
    val rest = uri.substring(SignInHosts.PREFIX.length)
    val end = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
    val host = rest.substring(0, end)
    if (!SignInHosts.allows(host)) return null
    val tail = rest.substring(end)
    return ProxyTarget(host, if (tail.startsWith("/")) tail else "/$tail")
}
