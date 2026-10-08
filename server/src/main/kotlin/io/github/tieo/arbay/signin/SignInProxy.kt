package io.github.tieo.arbay.signin

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.charset
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory

/**
 * Kleinanzeigen's sign-in pages, shown to the user inside Arbay through a reverse proxy.
 *
 * The browser sees only Arbay's addresses under [SignInHosts.PREFIX] and the proxy's own session
 * cookie. The cookies the site sets live in a [CookieJar] here, and every request the proxy makes
 * carries them. Once the site answers that the jar holds a signed-in session, the jar's cookies go
 * to [handOver], which gives them to the sidecar's browser, and the proxy session ends.
 *
 * Nothing is logged but the host and status of each answer: a request body or a cookie holds the
 * user's password and session.
 */
class SignInProxy(
    /** Where an allowed host is reached: the site itself, except in tests, which point it at a stand-in. */
    var upstream: (host: String) -> String = { "https://$it" },
    /** Gives the cookies to the sidecar's browser; true when that browser is then signed in. */
    private val handOver: suspend (List<ProxyCookie>) -> Boolean,
) {
    private val log = LoggerFactory.getLogger(SignInProxy::class.java)
    private val client = HttpClient(CIO) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The checks for a signed-in session run one after another, so none is dropped behind another. */
    private val checks = Mutex()

    /** The one sign-in going on: its id is the cookie the browser holds, and its jar is the site's cookies. */
    private class Session(val id: String, val jar: CookieJar, var lastUsed: Instant) {
        @Volatile var handedOver = false
    }

    @Volatile private var session: Session? = null

    /** Starts a sign-in, replacing any that was going on, and returns the id its browser cookie carries. */
    fun begin(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        session = Session(id, CookieJar(), Clock.System.now())
        return id
    }

    /** Whether a sign-in is going on: one that has been used within [IDLE_LIMIT]. */
    fun isActive(): Boolean = activeSession() != null

    /** Ends the sign-in going on, returning whether there was one. */
    fun end(): Boolean {
        val was = activeSession() != null
        session = null
        return was
    }

    private fun activeSession(): Session? {
        val current = session ?: return null
        if (Clock.System.now() - current.lastUsed > IDLE_LIMIT) {
            session = null
            return null
        }
        return current
    }

    /** Answers one request to the proxy: asks the site for it with the sign-in's cookies and passes its answer on. */
    suspend fun handle(call: ApplicationCall) {
        val current = activeSession()
        if (current == null || call.request.cookies[COOKIE_NAME] != current.id) {
            call.respondText("No sign-in is going on in this browser", status = HttpStatusCode.Forbidden)
            return
        }
        val target = parseProxyTarget(call.request.uri)
        if (target == null) {
            call.respondText("Not a Kleinanzeigen page", status = HttpStatusCode.NotFound)
            return
        }
        val now = Clock.System.now()
        current.lastUsed = now
        val body = call.receive<ByteArray>()
        val base = runCatching { URI.create("https://${target.host}${target.pathAndQuery}") }.getOrNull()
        if (base == null) {
            call.respondText("Not a Kleinanzeigen page", status = HttpStatusCode.BadRequest)
            return
        }

        val answer = client.request(upstream(target.host) + target.pathAndQuery) {
            method = call.request.httpMethod
            headers {
                // What the page's own requests carry, minus the cookies (the jar's) and the proxy's own addresses.
                call.request.headers[HttpHeaders.UserAgent]?.let { append(HttpHeaders.UserAgent, it) }
                call.request.headers[HttpHeaders.AcceptLanguage]?.let { append(HttpHeaders.AcceptLanguage, it) }
                call.request.headers[HttpHeaders.Accept]?.let { append(HttpHeaders.Accept, it) }
                call.request.headers[HttpHeaders.Referrer]?.let { append(HttpHeaders.Referrer, toSite(it)) }
                if (call.request.headers[HttpHeaders.Origin] != null) append(HttpHeaders.Origin, "https://${target.host}")
                current.jar.header(target.host, target.path, now)?.let { append(HttpHeaders.Cookie, it) }
                // Only the encodings that decodeBody reads; a page sent with another would not be rewritable.
                append(HttpHeaders.AcceptEncoding, "gzip, deflate")
            }
            if (body.isNotEmpty()) contentType(call.request.contentType())
            if (body.isNotEmpty()) setBody(body)
        }
        val setCookies = answer.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        setCookies.forEach { current.jar.store(target.host, target.path, it, now) }
        log.info("Sign-in proxy {} {} answered {}", call.request.httpMethod.value, target.host, answer.status.value)
        // A sign-in ends on www, whichever page of the chain set the session cookies, so every answer from there asks.
        if (target.host == WWW) checkSignedIn(current)

        val plain = decodeBody(answer.bodyAsBytes(), answer.headers[HttpHeaders.ContentEncoding])
        if (plain == null) {
            call.respondText("Kleinanzeigen sent a page this proxy cannot read", status = HttpStatusCode.BadGateway)
            return
        }
        val type = answer.contentType()
        val charset = type?.charset() ?: Charsets.UTF_8
        val (out, outType) = when {
            type?.match(ContentType.Text.Html) == true ->
                PageRewriter.html(plain.toString(charset), base).toByteArray(Charsets.UTF_8) to ContentType.Text.Html.withCharset(Charsets.UTF_8)
            type?.match(ContentType.Text.CSS) == true ->
                PageRewriter.css(plain.toString(charset), base).toByteArray(Charsets.UTF_8) to ContentType.Text.CSS.withCharset(Charsets.UTF_8)
            type?.match(ContentType.Text.JavaScript) == true || type?.match(ContentType.Application.JavaScript) == true ->
                PageRewriter.script(plain.toString(charset)).toByteArray(Charsets.UTF_8) to ContentType.Application.JavaScript.withCharset(Charsets.UTF_8)
            else -> plain to (type ?: ContentType.Application.OctetStream)
        }
        // A page's own document is never cached: it carries the sign-in's state and the rewritten addresses.
        val document = outType.match(ContentType.Text.Html)
        for ((name, values) in answer.headers.entries()) {
            val lower = name.lowercase()
            if (lower in DROPPED_RESPONSE_HEADERS || (document && lower == "cache-control")) continue
            for (value in values) {
                val forwarded = if (lower == "location") PageRewriter.location(value, base) else value
                call.response.headers.append(name, forwarded)
            }
        }
        // The page's own policy would stop Arbay framing it; only Arbay's own pages may frame it.
        call.response.headers.append("Content-Security-Policy", "frame-ancestors 'self'")
        if (document) call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respondBytes(out, outType, HttpStatusCode.fromValue(answer.status.value))
    }

    /** Asks the site whether the jar holds a signed-in session, and hands the jar over when it does. */
    private fun checkSignedIn(session: Session) {
        if (session.handedOver) return
        scope.launch {
            checks.withLock {
                try {
                    if (session.handedOver) return@withLock
                    val now = Clock.System.now()
                    val cookie = session.jar.header(WWW, TOKEN_PATH, now) ?: return@withLock
                    val answer = client.get(upstream(WWW) + TOKEN_PATH) { headers { append(HttpHeaders.Cookie, cookie) } }
                    answer.headers.getAll(HttpHeaders.SetCookie).orEmpty().forEach { session.jar.store(WWW, TOKEN_PATH, it, now) }
                    val signedIn = answer.status == HttpStatusCode.OK && answer.headers[HttpHeaders.Authorization] != null
                    if (!signedIn) return@withLock
                    session.handedOver = handOver(session.jar.all(now))
                    if (session.handedOver && this@SignInProxy.session?.id == session.id) end()
                } catch (e: Exception) {
                    log.warn("Sign-in hand-over did not finish: {}", e.message)
                }
            }
        }
    }

    /** A page's address on the proxy, turned back into the site's own, for the headers the site reads. */
    private fun toSite(value: String): String {
        val at = value.indexOf(SignInHosts.PREFIX)
        return if (at < 0) value else "https://" + value.substring(at + SignInHosts.PREFIX.length)
    }

    companion object {
        /** The cookie that ties the iframe's requests to the sign-in; it is only sent to the proxy's paths. */
        const val COOKIE_NAME = "arbay_signin"
        const val COOKIE_PATH = "/signin-proxy"

        private const val WWW = "www.kleinanzeigen.de"
        private const val TOKEN_PATH = "/m-access-token.json"
        private val IDLE_LIMIT = 30.minutes

        /** Headers that describe the site's own transport or policy, which would be wrong once the page is Arbay's. */
        private val DROPPED_RESPONSE_HEADERS = setOf(
            "content-security-policy", "content-security-policy-report-only", "x-frame-options",
            "strict-transport-security", "alt-svc", "link", "set-cookie", "content-length",
            "content-type", "content-encoding", "transfer-encoding", "connection", "keep-alive", "etag", "last-modified",
            "report-to", "reporting-endpoints", "nel", "cross-origin-embedder-policy", "cross-origin-resource-policy",
        )

        /**
         * The body with its gzip or deflate coding removed, or null when the coding is one this proxy
         * cannot read. Only gzip and deflate are asked for, so anything else is an answer to be refused.
         */
        internal fun decodeBody(bytes: ByteArray, encoding: String?): ByteArray? = runCatching {
            when (encoding?.trim()?.lowercase()) {
                null, "", "identity" -> bytes
                "gzip", "x-gzip" -> GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
                "deflate" -> try {
                    InflaterInputStream(bytes.inputStream()).use { it.readBytes() }
                } catch (e: java.util.zip.ZipException) {
                    // Some servers send raw deflate, without the zlib header.
                    InflaterInputStream(bytes.inputStream(), Inflater(true)).use { it.readBytes() }
                }
                else -> null
            }
        }.getOrNull()
    }
}
