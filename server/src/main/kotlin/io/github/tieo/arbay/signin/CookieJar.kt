package io.github.tieo.arbay.signin

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A cookie a Kleinanzeigen page set during a sign-in. A [hostOnly] cookie goes back to [domain] alone;
 * any other goes back to [domain] and its subdomains.
 */
data class ProxyCookie(
    val name: String,
    val value: String,
    val domain: String,
    val hostOnly: Boolean,
    val path: String,
    val secure: Boolean,
    val httpOnly: Boolean,
    val expires: Instant?,
)

/**
 * The cookies of one sign-in, kept on the server. The browser only holds the proxy's session id, and
 * each request the proxy makes carries the cookies the site would have had from this browser.
 */
class CookieJar {
    private val cookies = LinkedHashMap<Triple<String, String, String>, ProxyCookie>()

    /** Keeps the cookie of [header], a Set-Cookie answered to a request for [requestPath] on [host]. */
    @Synchronized
    fun store(host: String, requestPath: String, header: String, now: Instant) {
        val parts = header.split(';').map { it.trim() }
        val pair = parts.first()
        val eq = pair.indexOf('=')
        if (eq <= 0) return
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim()
        var domain = host
        var hostOnly = true
        var path = requestPath.substringBeforeLast('/', "").ifEmpty { "/" }
        var maxAge: Long? = null
        var expires: Instant? = null
        var secure = false
        var httpOnly = false
        for (attribute in parts.drop(1)) {
            val argument = attribute.substringAfter('=', "").trim()
            when (attribute.substringBefore('=').trim().lowercase()) {
                "domain" -> {
                    val named = argument.trimStart('.').lowercase()
                    if (named.isNotEmpty()) {
                        domain = named
                        hostOnly = false
                    }
                }
                "path" -> if (argument.startsWith("/")) path = argument
                "max-age" -> maxAge = argument.toLongOrNull()
                "expires" -> expires = parseHttpDate(argument)
                "secure" -> secure = true
                "httponly" -> httpOnly = true
            }
        }
        // A cookie for another site is refused, even when the page that sent it is one of the allowed hosts.
        if (!allowedDomain(host, domain)) return
        val end = maxAge?.let { now + it.seconds } ?: expires
        val key = Triple(domain, path, name)
        val expired = maxAge?.let { it <= 0 } ?: (end != null && end <= now)
        if (expired) {
            cookies.remove(key)
            return
        }
        cookies[key] = ProxyCookie(name, value, domain, hostOnly, path, secure, httpOnly, end)
    }

    /** The Cookie header a request for [path] on [host] carries, or null when no cookie applies. */
    @Synchronized
    fun header(host: String, path: String, now: Instant): String? =
        applicable(host, path, now).takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }

    /** Every cookie the jar still holds, for handing the sign-in over to another browser. */
    @Synchronized
    fun all(now: Instant): List<ProxyCookie> {
        expire(now)
        return cookies.values.toList()
    }

    private fun applicable(host: String, path: String, now: Instant): List<ProxyCookie> {
        expire(now)
        return cookies.values
            .filter { domainMatches(it, host) && pathMatches(it.path, path) }
            .sortedByDescending { it.path.length }
    }

    private fun expire(now: Instant) {
        cookies.values.removeAll { it.expires != null && it.expires <= now }
    }

    private fun allowedDomain(host: String, domain: String): Boolean =
        (host == domain || host.endsWith(".$domain")) &&
            (domain == SignInHosts.DOMAIN || domain.endsWith(".${SignInHosts.DOMAIN}"))

    private fun domainMatches(cookie: ProxyCookie, host: String): Boolean =
        if (cookie.hostOnly) host == cookie.domain else host == cookie.domain || host.endsWith(".${cookie.domain}")

    private fun pathMatches(cookiePath: String, requestPath: String): Boolean =
        requestPath == cookiePath ||
            (requestPath.startsWith(cookiePath) && (cookiePath.endsWith("/") || requestPath[cookiePath.length] == '/'))

    private fun parseHttpDate(text: String): Instant? = runCatching {
        Instant.fromEpochMilliseconds(ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli())
    }.getOrNull()
}
