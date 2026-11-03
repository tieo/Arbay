package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.SiteGaps
import io.github.tieo.arbay.crawler.SiteRecipes
import io.github.tieo.arbay.plugins.BadRequestException
import io.github.tieo.arbay.plugins.NotFoundException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * The web market's sites: the ones it could not read, the recipes it reads others by, and a way to
 * try a recipe on a real page before keeping it. What the agent extending the web market works with.
 */
fun Route.webRoutes() {
    route("/api/web") {
        // The sites found selling something and not read, after the one numbered `after`; waits up
        // to `waitSeconds` (at most a minute) for one when there is none yet.
        get("/gaps") {
            val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0L
            val wait = (call.request.queryParameters["waitSeconds"]?.toIntOrNull() ?: 0).coerceIn(0, 60)
            call.respond(SiteGaps.after(after, kotlin.time.Duration.parse("${wait}s")))
        }
        get("/gaps/{domain}/page") {
            val domain = call.parameters["domain"] ?: throw BadRequestException("Missing domain")
            call.respondText(SiteGaps.page(domain) ?: throw NotFoundException("No page kept for $domain"), ContentType.Text.Html)
        }
        // A site that cannot be taught (it blocks every way in, or sells nothing after all): the
        // searches waiting on it stop waiting.
        post("/gaps/{domain}/given-up") {
            val domain = call.parameters["domain"] ?: throw BadRequestException("Missing domain")
            SiteGaps.giveUp(SiteRecipes.domainOf("https://$domain/"))
            SiteGaps.learned.emit(SiteGaps.Learned(domain, emptyMap()))
            call.respond(HttpStatusCode.NoContent)
        }
        get("/recipes") { call.respondText(SiteRecipes.recipes().toString(), ContentType.Application.Json) }
        // What the search engines list for any words, not only shops: each engine's results with
        // title, address and snippet, and all merged by address. `engine` may repeat to pick some.
        get("/serp") {
            val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("Missing q")
            val engines = call.request.queryParameters.getAll("engine").orEmpty().toSet()
            val web = CrawlerRegistry.crawlerFor(io.github.tieo.arbay.model.PlatformId.WEB) as? io.github.tieo.arbay.crawler.WebCrawler
                ?: throw NotFoundException("The web market is not running")
            call.respond(web.engines.serp(q, engines))
        }
        // The pictures Bing Images finds for any words, each with the page it stands on.
        get("/images") {
            val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() } ?: throw BadRequestException("Missing q")
            val pages = call.request.queryParameters["pages"]?.toIntOrNull()?.coerceIn(1, 10) ?: 3
            val web = CrawlerRegistry.crawlerFor(io.github.tieo.arbay.model.PlatformId.WEB) as? io.github.tieo.arbay.crawler.WebCrawler
                ?: throw NotFoundException("The web market is not running")
            call.respond(web.engines.images(q, pages))
        }
        put("/recipes") {
            val recipe = call.receive<kotlinx.serialization.json.JsonObject>()
            val domain = (recipe["domain"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                ?: throw BadRequestException("A recipe needs its domain")
            try {
                SiteRecipes.put(recipe)
            } catch (e: io.github.tieo.arbay.neuland.Neuland.Refusal) {
                throw BadRequestException(e.message ?: "not a recipe")
            }
            SiteGaps.takeBack(SiteRecipes.domainOf("https://$domain/"))
            // What the searches that met the site before it was learned missed, read now and added
            // to them, the ones still open on a screen included.
            val learned = (CrawlerRegistry.crawlerFor(io.github.tieo.arbay.model.PlatformId.WEB) as? io.github.tieo.arbay.crawler.WebCrawler)
                ?.learned(SiteRecipes.domainOf("https://$domain/")).orEmpty()
            call.respond(learned.mapValues { it.value.size })
        }
        delete("/recipes/{domain}") {
            SiteRecipes.remove(call.parameters["domain"] ?: throw BadRequestException("Missing domain"))
            call.respond(HttpStatusCode.NoContent)
        }
        // A recipe tried on a real page before it is kept: in the request, else the site's own; on the
        // page as it was kept when the site was reported (`kept`), else fetched now.
        post("/try") {
            val asked = call.receive<kotlinx.serialization.json.JsonObject>()
            val url = (asked["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: throw BadRequestException("Missing url")
            val kept = (asked["kept"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            val html = if (kept) SiteGaps.page(SiteRecipes.domainOf(url)) ?: throw NotFoundException("No page kept for ${SiteRecipes.domainOf(url)}") else null
            call.respondText(SiteRecipes.tryOn(url, asked["recipe"], html).toString(), ContentType.Application.Json)
        }
    }
}
