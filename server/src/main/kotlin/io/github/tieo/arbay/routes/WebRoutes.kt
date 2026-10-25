package io.github.tieo.arbay.routes

import io.github.tieo.arbay.crawler.CrawlerRegistry
import io.github.tieo.arbay.crawler.PageOffer
import io.github.tieo.arbay.crawler.ProductPages
import io.github.tieo.arbay.crawler.SiteGaps
import io.github.tieo.arbay.crawler.SiteRecipe
import io.github.tieo.arbay.crawler.SiteRecipes
import io.github.tieo.arbay.crawler.fetchWithFallback
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
import kotlinx.serialization.Serializable

/** A page to read, with the recipe to read it by; the site's stored one, or none, where absent. */
@Serializable
data class WebTry(
    val url: String,
    val recipe: SiteRecipe? = null,
    /** Read the page as it was kept when the site was reported, instead of fetching it again. */
    val kept: Boolean = false,
)

/** What a page gave: its offers, what read them, and whether the page sells something at all. */
@Serializable
data class WebTried(
    val offers: List<PageOffer>,
    val readBy: String,
    val sellsSomething: Boolean,
    val pageLength: Int,
    /** Whether the page passes itself off as another shop, which a search leaves out. */
    val impersonates: Boolean = false,
)

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
        get("/recipes") { call.respond(SiteRecipes.all()) }
        put("/recipes") {
            val recipe = call.receive<SiteRecipe>()
            if (recipe.domain.isBlank()) throw BadRequestException("A recipe needs its domain")
            // A recipe of a search alone is a site whose structured data reads it, searched directly.
            if (recipe.pattern == null && recipe.searchUrl == null && (recipe.title.isBlank() || recipe.price.isBlank()))
                throw BadRequestException("A recipe needs its title and price, a pattern, or a searchUrl")
            recipe.urlPattern?.let { runCatching { Regex(it) }.getOrElse { throw BadRequestException("urlPattern is not a regex: ${it.message}") } }
            recipe.pattern?.let { p ->
                runCatching { Regex(p) }.getOrElse { throw BadRequestException("pattern is not a regex: ${it.message}") }
                if ("(?<title>" !in p || "(?<price>" !in p) throw BadRequestException("pattern needs the groups (?<title>...) and (?<price>...)")
            }
            SiteRecipes.put(recipe)
            SiteGaps.takeBack(SiteRecipes.domainOf("https://${recipe.domain}/"))
            // What the searches that met the site before it was learned missed, read now and added
            // to them, the ones still open on a screen included.
            val learned = (CrawlerRegistry.crawlerFor(io.github.tieo.arbay.model.PlatformId.WEB) as? io.github.tieo.arbay.crawler.WebCrawler)
                ?.learned(SiteRecipes.domainOf("https://${recipe.domain}/")).orEmpty()
            call.respond(learned.mapValues { it.value.size })
        }
        delete("/recipes/{domain}") {
            SiteRecipes.remove(call.parameters["domain"] ?: throw BadRequestException("Missing domain"))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/try") {
            val asked = call.receive<WebTry>()
            val domain = SiteRecipes.domainOf(asked.url)
            val recipe = asked.recipe ?: SiteRecipes.forUrl(asked.url)
            val html = if (asked.kept) SiteGaps.page(domain) ?: throw NotFoundException("No page kept for $domain")
            else if (recipe?.items != null) io.github.tieo.arbay.crawler.WebCrawler.apiAnswer(CrawlerRegistry.httpClient, asked.url)
            else if (recipe?.render == true || io.github.tieo.arbay.crawler.RenderedSites.has(domain))
                io.github.tieo.arbay.crawler.WebCrawler.rendered(asked.url) ?: throw NotFoundException("The browser did not answer for ${asked.url}")
            else fetchWithFallback(CrawlerRegistry.httpClient, asked.url, "Web $domain")
            // A recipe in the request is tried alone; without one, the site's recipes as a search reads them.
            val byRecipe = asked.recipe?.let { SiteRecipes.read(it, html, asked.url) } ?: SiteRecipes.readAt(asked.url, html)
            val offers = SiteRecipes.asTheSiteDeals(asked.url, html, byRecipe.ifEmpty { SiteRecipes.withoutOffersReadElsewhere(asked.url, ProductPages.offers(html, asked.url)) })
            call.respond(WebTried(
                offers = offers,
                readBy = when {
                    byRecipe.isNotEmpty() -> "recipe"
                    offers.isNotEmpty() -> "structured data"
                    else -> "nothing"
                },
                sellsSomething = ProductPages.sellsSomething(html),
                pageLength = html.length,
                impersonates = ProductPages.impersonates(html, asked.url),
            ))
        }
    }
}
