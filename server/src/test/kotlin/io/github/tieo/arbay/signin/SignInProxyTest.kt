package io.github.tieo.arbay.signin

import io.github.tieo.arbay.routes.signInProxyRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * The proxy against a stand-in for the site: a local server that answers the same paths the login
 * uses, and sends the browser through the same redirect chain (identifier, password, callback to www).
 * No request goes to Kleinanzeigen, and the credentials typed here are made up.
 */
class SignInProxyTest {
    private val site = FakeSignInSite()
    private val received get() = site.received
    private val port = ServerSocket(0).use { it.localPort }
    private val standIn = site.start(port)
    private val handedOver = CompletableDeferred<List<ProxyCookie>>()
    private val proxy = SignInProxy(
        upstream = { "http://127.0.0.1:$port" },
        handOver = { cookies ->
            if (!handedOver.isCompleted) handedOver.complete(cookies)
            true
        },
    )

    @AfterTest
    fun stopStandIn() {
        standIn.stop(0, 0)
    }

    private suspend fun awaitReceived(path: String) {
        withTimeout(10_000) { while (received.none { it.path == path }) delay(20) }
    }

    @Test
    fun theSignInRunsThroughTheProxyAndTheSitesCookiesStayOnTheServer() = testApplication {
        application { routing { signInProxyRoutes(proxy) } }
        val client = createClient { followRedirects = false }
        val cookie = "${SignInProxy.COOKIE_NAME}=${proxy.begin()}"

        val start = client.get("/signin-proxy/www.kleinanzeigen.de/m-einloggen.html") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.Found, start.status)
        assertEquals("/signin-proxy/login.kleinanzeigen.de/authorize?state=s1", start.headers[HttpHeaders.Location])
        assertNull(start.headers[HttpHeaders.SetCookie], "a site cookie reached the browser")

        val authorize = client.get(start.headers[HttpHeaders.Location]!!) { header(HttpHeaders.Cookie, cookie) }
        assertEquals("/signin-proxy/login.kleinanzeigen.de/u/login/identifier?state=s1", authorize.headers[HttpHeaders.Location])
        assertNull(authorize.headers[HttpHeaders.SetCookie], "a site cookie reached the browser")

        val page = client.get(authorize.headers[HttpHeaders.Location]!!) { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, page.status)
        assertEquals("frame-ancestors 'self'", page.headers["Content-Security-Policy"])
        assertNull(page.headers["X-Frame-Options"])
        assertEquals(1, page.headers.getAll(HttpHeaders.ContentType)?.size, "the page's content type was sent twice")
        assertEquals("no-store", page.headers[HttpHeaders.CacheControl])
        val html = page.bodyAsText()
        assertContains(html, """action="/signin-proxy/login.kleinanzeigen.de/u/login/identifier?state=s1"""")
        assertFalse(html.contains("https://login.kleinanzeigen.de"), "an absolute address of the login host was left in the page")
        assertFalse(html.contains("integrity="))
        // The cookie set by the authorize step went into the jar and comes back on the page's own request.
        assertContains(received.last { it.path == "/u/login/identifier" && it.body.isEmpty() }.cookie.orEmpty(), "did=d1")

        val submitted = client.post("/signin-proxy/login.kleinanzeigen.de/u/login/identifier?state=s1") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("username=user%40example.test&action=default")
        }
        assertEquals("/signin-proxy/login.kleinanzeigen.de/u/login/password?state=s1", submitted.headers[HttpHeaders.Location])
        val postedIdentifier = received.last { it.path == "/u/login/identifier" && it.body.isNotEmpty() }
        assertEquals("username=user%40example.test&action=default", postedIdentifier.body)
        assertContains(postedIdentifier.contentType.orEmpty(), "application/x-www-form-urlencoded")

        // The password page is gzipped by the stand-in; the proxy reads it and the browser gets the page.
        val passwordPage = client.get("/signin-proxy/login.kleinanzeigen.de/u/login/password?state=s1") { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, passwordPage.status)
        assertNull(passwordPage.headers[HttpHeaders.ContentEncoding])
        assertContains(passwordPage.bodyAsText(), "type=\"password\"")

        val done = client.post("/signin-proxy/login.kleinanzeigen.de/u/login/password?state=s1") {
            header(HttpHeaders.Cookie, cookie)
            header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
            setBody("password=not-a-real-password")
        }
        assertEquals("/signin-proxy/www.kleinanzeigen.de/m-einloggen-callback.html?code=ok", done.headers[HttpHeaders.Location])
        assertNull(done.headers[HttpHeaders.SetCookie], "a site cookie reached the browser")

        val callback = client.get(done.headers[HttpHeaders.Location]!!) { header(HttpHeaders.Cookie, cookie) }
        assertEquals("/signin-proxy/www.kleinanzeigen.de/", callback.headers[HttpHeaders.Location])
        val sentToCallback = received.last { it.path == "/m-einloggen-callback.html" }.cookie.orEmpty()
        assertContains(sentToCallback, "auth=ok")
        assertContains(sentToCallback, "CSRF-TOKEN=t1")
        assertFalse(sentToCallback.contains("did=d1"), "a login-host cookie was sent to www")

        // The callback set a cookie on www, so the proxy asked the site for the access token and got a signed-in answer.
        val cookies = withTimeout(10_000) { handedOver.await() }
        val auth = cookies.single { it.name == "auth" }
        assertEquals("kleinanzeigen.de", auth.domain)
        assertFalse(auth.hostOnly)
        val did = cookies.single { it.name == "did" }
        assertEquals("login.kleinanzeigen.de", did.domain)
        assertTrue(did.hostOnly)
        assertTrue(did.secure && did.httpOnly)
        assertContains(received.last { it.path == "/m-access-token.json" }.cookie.orEmpty(), "auth=ok")
        withTimeout(10_000) { while (proxy.isActive()) delay(20) }
    }

    @Test
    fun aRequestWithoutTheSignInsCookieIsRefusedAndNothingIsFetched() = testApplication {
        application { routing { signInProxyRoutes(proxy) } }
        proxy.begin()
        val refused = client.get("/signin-proxy/www.kleinanzeigen.de/m-einloggen.html")
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(received.isEmpty())
    }

    @Test
    fun aHostOutsideTheAllowlistIsNeverRequested() = testApplication {
        application { routing { signInProxyRoutes(proxy) } }
        val cookie = "${SignInProxy.COOKIE_NAME}=${proxy.begin()}"
        for (path in listOf("/signin-proxy/evil.example/steal", "/signin-proxy/themen.kleinanzeigen.de/", "/signin-proxy/www.kleinanzeigen.de@evil.example/x")) {
            val answer = client.get(path) { header(HttpHeaders.Cookie, cookie) }
            assertEquals(HttpStatusCode.NotFound, answer.status, path)
        }
        assertTrue(received.isEmpty())
    }

    @Test
    fun aSignedOutSiteIsNeverHandedOver() = testApplication {
        application { routing { signInProxyRoutes(proxy) } }
        val cookie = "${SignInProxy.COOKIE_NAME}=${proxy.begin()}"
        client.get("/signin-proxy/www.kleinanzeigen.de/m-einloggen.html") { header(HttpHeaders.Cookie, cookie) }
        // The check that the site's answer triggers is asked for, and answers no session.
        awaitReceived("/m-access-token.json")
        delay(300)
        assertFalse(handedOver.isCompleted)
        assertTrue(proxy.isActive())
    }

    @Test
    fun theProxyEndsWhenTheSignInIsCancelled() = testApplication {
        application { routing { signInProxyRoutes(proxy) } }
        val id = proxy.begin()
        assertTrue(proxy.isActive())
        assertTrue(proxy.end())
        assertFalse(proxy.end())
        val answer = client.get("/signin-proxy/www.kleinanzeigen.de/") { header(HttpHeaders.Cookie, "${SignInProxy.COOKIE_NAME}=$id") }
        assertEquals(HttpStatusCode.Forbidden, answer.status)
    }
}
