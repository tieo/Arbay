package io.github.tieo.arbay.routes

import io.github.tieo.arbay.plugins.NotFoundException
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.http.content.CompressedFileType
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The browser app, when this server was built with it (see the shadowJar task): its files at the
 * root, and its page for every address the app itself gives a place a name, so a saved search,
 * a listing or Settings opened from a bookmark or a new tab lands where it was.
 *
 * None of its files is named by its content, so a browser asks about each again before using the
 * copy it has; the answer is a short "not changed" until a deploy changes it.
 */
fun Route.webAppRoutes() {
    staticResources("/", "web", index = "index.html") {
        preCompressed(CompressedFileType.GZIP)
        cacheControl { listOf(CacheControl.NoCache(null)) }
    }
    val page by lazy { WebAppPage::class.java.classLoader.getResource("web/index.html")?.readBytes() }
    for (place in listOf("/saved/{rest...}", "/search/{rest...}", "/free", "/settings")) {
        get(place) {
            val html = page ?: throw NotFoundException("This server was built without the web app")
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            call.respondBytes(html, ContentType.Text.Html)
        }
    }
}

private object WebAppPage
