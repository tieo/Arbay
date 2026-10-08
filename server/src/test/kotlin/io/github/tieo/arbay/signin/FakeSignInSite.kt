package io.github.tieo.arbay.signin

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.contentType
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream

/** A one-pixel transparent PNG, for the images the stand-in's pages ask for. */
private const val PIXEL = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="

/**
 * A stand-in for the site's sign-in chain, served on localhost: www sends the browser to the login host,
 * the login host runs identifier and password steps, and its callback sets the session cookie on www.
 * Every path answers whichever host the proxy was told to reach, so both hosts map to one server.
 * The credentials it receives are made up; nothing here talks to Kleinanzeigen.
 */
class FakeSignInSite {
    /** What the stand-in received: the path, the cookie header, the body and the content type. */
    class Received(val path: String, val cookie: String?, val body: String, val contentType: String?)

    val received = CopyOnWriteArrayList<Received>()

    /** Starts the stand-in on [port] of localhost. */
    fun start(port: Int): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(Netty, port = port, host = "127.0.0.1") { routes() }.start(wait = false)

    private fun Application.routes() {
        val identifier = javaClass.getResource("/fixtures/signin_identifier_synthetic.html")!!.readText()
        routing {
            get("/m-einloggen.html") {
                note(call)
                call.response.headers.append(HttpHeaders.SetCookie, "CSRF-TOKEN=t1; Domain=.kleinanzeigen.de; Path=/")
                redirect(call, "https://login.kleinanzeigen.de/authorize?state=s1")
            }
            get("/authorize") {
                note(call)
                call.response.headers.append(HttpHeaders.SetCookie, "did=d1; Path=/; HttpOnly; Secure")
                redirect(call, "/u/login/identifier?state=s1")
            }
            get("/u/login/identifier") {
                note(call)
                call.response.headers.append("Content-Security-Policy", "frame-ancestors 'none'")
                call.response.headers.append("X-Frame-Options", "DENY")
                call.respondText(identifier, ContentType.Text.Html)
            }
            post("/u/login/identifier") {
                note(call, call.receiveText())
                redirect(call, "/u/login/password?state=s1")
            }
            get("/u/login/password") {
                note(call)
                // Gzipped when asked for, as the site sends it, so the proxy has to read the coding.
                val html = "<html><body><form method=\"post\" action=\"/u/login/password?state=s1\"><input type=\"password\" name=\"password\"></form></body></html>"
                if (call.request.headers[HttpHeaders.AcceptEncoding]?.contains("gzip") == true) {
                    call.response.headers.append(HttpHeaders.ContentEncoding, "gzip")
                    call.respondBytes(gzip(html), ContentType.Text.Html)
                } else {
                    call.respondText(html, ContentType.Text.Html)
                }
            }
            post("/u/login/password") {
                note(call, call.receiveText())
                call.response.headers.append(HttpHeaders.SetCookie, "auth=ok; Domain=.kleinanzeigen.de; Path=/")
                redirect(call, "https://www.kleinanzeigen.de/m-einloggen-callback.html?code=ok")
            }
            get("/m-einloggen-callback.html") {
                note(call)
                redirect(call, "https://www.kleinanzeigen.de/")
            }
            get("/") {
                note(call)
                call.respondText("home", ContentType.Text.Html)
            }
            // The identifier page's own resources, so the page loads without errors behind the proxy.
            get("/static/{file...}") {
                note(call)
                val file = call.request.path()
                when {
                    file.endsWith(".js") -> call.respondText("/* stand-in */", ContentType.Application.JavaScript)
                    file.endsWith(".css") -> call.respondText("/* stand-in */", ContentType.Text.CSS)
                    file.endsWith(".svg") -> call.respondText("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"></svg>", ContentType.Image.SVG)
                    file.endsWith(".png") -> call.respondBytes(java.util.Base64.getDecoder().decode(PIXEL), ContentType.Image.PNG)
                    else -> call.respondText("", status = HttpStatusCode.NotFound)
                }
            }
            get("/favicon.ico") { call.respondText("", status = HttpStatusCode.NoContent) }
            get("/m-access-token.json") {
                note(call)
                if (call.request.headers[HttpHeaders.Cookie]?.contains("auth=ok") == true) {
                    call.response.headers.append(HttpHeaders.Authorization, "Bearer test-token")
                    call.respondText("{}", ContentType.Application.Json)
                } else {
                    call.respondText("{}", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                }
            }
        }
    }

    private suspend fun note(call: ApplicationCall, body: String = "") {
        received += Received(call.request.path(), call.request.headers[HttpHeaders.Cookie], body, call.request.headers[HttpHeaders.ContentType])
    }

    private suspend fun redirect(call: ApplicationCall, to: String) {
        call.response.headers.append(HttpHeaders.Location, to)
        call.respondText("", status = HttpStatusCode.Found)
    }

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }
}
