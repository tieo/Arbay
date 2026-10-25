package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.Seller
import io.github.tieo.arbay.model.SellerType
import io.github.tieo.arbay.model.Shipping
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import java.security.MessageDigest
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Every shop on the web, beyond the markets Arbay has a crawler of its own for.
 *
 * The words go to a search engine (the SearXNG beside the server, which asks Bing, DuckDuckGo and
 * the others that answer it) and to the own search of every site Arbay has learned to read. Each
 * page found is read for its offers: the structured data shops publish for search engines first
 * ([ProductPages]), the site's [SiteRecipe] where it has one. A page that sells something and
 * yields nothing is reported to [SiteGaps], whose reader writes the site a recipe, so the next
 * search reads it. What the pages hold beyond the thing searched for is left to the relevance
 * filter, as on every other market.
 */
class WebCrawler(
    private val client: HttpClient,
    private val searxUrl: String? = System.getenv("ARBAY_SEARXNG_URL"),
) : Crawler {
    override val platformId = PlatformId.WEB
    private val log = LoggerFactory.getLogger(WebCrawler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val base = searxUrl?.trimEnd('/')
            ?: throw CrawlerBlockedException("Web: no search engine configured (ARBAY_SEARXNG_URL)", ErrorType.SERVICE_UNAVAILABLE_503)
        val words = query.positiveText
        // The engine's pages, then each learned site's own search: a site that has a recipe is
        // asked directly, so it is not left to whether an engine happens to list it.
        val found = (engineResults(base, words) + browserEngineResults(words) + siteSearches(words)).distinct()
            .filterNot { coveredElsewhere(it) }
            .take(MAX_PAGES)
        log.info("Web: '{}' reads {} pages", words, found.size)
        val now = Clock.System.now()
        val gate = Semaphore(PARALLEL_PAGES)
        val offers = coroutineScope {
            found.map { url -> async { gate.withPermit { withTimeoutOrNull(PAGE_TIMEOUT_MS) { read(url, words) }.orEmpty() } } }.awaitAll()
        }.flatten()
        return offers.filter { it.inStock }.map { o ->
            val domain = SiteRecipes.domainOf(o.url)
            Listing(
                id = "${platformId.name}:${idOf(o.url, o.title)}",
                platformId = platformId,
                externalId = idOf(o.url, o.title),
                url = o.url,
                title = o.title,
                price = o.price,
                condition = o.condition,
                imageUrls = listOfNotNull(o.image),
                seller = Seller(name = o.seller ?: domain, type = SellerType.BUSINESS, url = "https://$domain"),
                shipping = o.shipping?.let { Shipping(cost = it, free = it.amount == 0L) },
                gtin = o.gtin,
                mpn = o.mpn,
                scrapedAt = now,
            )
        }.distinctBy { it.id }
    }

    /** The result pages the engine lists for the words, and for them with a word to buy. */
    private suspend fun engineResults(base: String, words: String): List<String> =
        listOf(words, "$words kaufen").flatMap { q ->
            (1..ENGINE_PAGES).flatMap { page ->
                try {
                    val body = client.get("$base/search") {
                        parameter("q", q); parameter("format", "json"); parameter("language", "de-DE"); parameter("pageno", page)
                    }.bodyAsText()
                    val root = json.parseToJsonElement(body) as JsonObject
                    (root["unresponsive_engines"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let {
                        log.debug("Web: engines not answering for '{}': {}", q, it)
                    }
                    (root["results"] as? JsonArray).orEmpty().mapNotNull { r ->
                        (r as? JsonObject)?.get("url")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") }
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    log.warn("Web: search engine failed for '{}': {}", q, e.message?.take(100))
                    emptyList()
                }
            }
        }

    /**
     * The result pages of the engines that answer only a browser, read in [SearchBrowser]: from the
     * server's address, Google and Qwant refuse SearXNG's plain requests with "access denied", Brave
     * with "too many requests", and Startpage answers them with nothing.
     */
    private suspend fun browserEngineResults(words: String): List<String> = BROWSER_ENGINES.flatMap { engine ->
        val page = SearchBrowser.get(engine.url(words), engine.markers, timeoutS = 25)
        if (page == null || page.page != "content") {
            log.warn("Web: {} gave no results page for '{}' ({})", engine.name, words, page?.page ?: "browser did not answer")
            return@flatMap emptyList()
        }
        resultLinks(page.html, engine.host).also { log.debug("Web: {} listed {} pages for '{}'", engine.name, it.size, words) }
    }

    private fun siteSearches(words: String): List<String> =
        SiteRecipes.all().mapNotNull { r -> r.searchUrl?.replace("{q}", words.encodeUrl()) }.distinct()

    /** The offers on one page; a page that sells something and yields none is reported. */
    private suspend fun read(url: String, words: String): List<PageOffer> {
        val domain = SiteRecipes.domainOf(url)
        val recipe = SiteRecipes.forUrl(url)
        val html = try {
            fetchWithFallback(client, url, "Web $domain", browserOnly = recipe?.render == true)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.debug("Web: {} not fetched: {}", url, e.message?.take(80))
            return emptyList()
        }
        val offers = try {
            recipe?.let { SiteRecipes.read(it, html, url) }.orEmpty().ifEmpty { ProductPages.offers(html, url) }
        } catch (e: Exception) {
            log.warn("Web: {} unreadable: {}", url, e.message?.take(80))
            emptyList()
        }
        if (offers.isEmpty() && ProductPages.sellsSomething(html)) {
            SiteGaps.record(domain, url, words, html, recipeBroken = recipe != null)
        }
        return offers
    }

    /** Whether the page belongs to a market Arbay crawls by its own crawler, which reads it better. */
    private fun coveredElsewhere(url: String): Boolean {
        val host = SiteRecipes.domainOf(url)
        return host.isEmpty() || COVERED.any { host == it || host.endsWith(".$it") } ||
            SKIPPED.any { host == it || host.endsWith(".$it") } || url.substringBefore('?').lowercase().endsWith(".pdf")
    }

    private fun idOf(url: String, title: String): String =
        MessageDigest.getInstance("SHA-1").digest("$url|$title".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** A search engine read in a browser: its results page for some words, and what shows it arrived. */
    private class BrowserEngine(val name: String, val host: String, val markers: List<String>, val url: (String) -> String)

    companion object {
        private val BROWSER_ENGINES = listOf(
            BrowserEngine("Google", "google.", listOf("id=\"rso\"", "id=\"search\"")) { q ->
                "https://www.google.com/search?q=${q.encodeUrl()}&hl=de&gl=de&num=20"
            },
            BrowserEngine("Qwant", "qwant.", listOf("data-testid=\"webResult\"", "data-testid=\"serTitle\"")) { q ->
                "https://www.qwant.com/?q=${q.encodeUrl()}&t=web&locale=de_DE"
            },
            BrowserEngine("Brave", "brave.", listOf("id=\"results\"", "data-type=\"web\"")) { q ->
                "https://search.brave.com/search?q=${q.encodeUrl()}&source=web"
            },
            BrowserEngine("Startpage", "startpage.", listOf("class=\"w-gl", "class=\"result")) { q ->
                "https://www.startpage.com/do/search?q=${q.encodeUrl()}&language=deutsch"
            },
        )

        /** The pages a results page links to, apart from the engine's own and its redirects'. */
        internal fun resultLinks(html: String, engineHost: String): List<String> =
            org.jsoup.Jsoup.parse(html).select("a[href]").mapNotNull { a ->
                val href = a.attr("href")
                // Google wraps some results in its own redirect: /url?q=<the page>&...
                val target = if (href.startsWith("/url?")) java.net.URLDecoder.decode(href.substringAfter("q=").substringBefore('&'), "UTF-8") else href
                target.takeIf { it.startsWith("http") }
            }.filter { url ->
                val host = SiteRecipes.domainOf(url)
                host.isNotEmpty() && engineHost !in host && ENGINE_HOSTS.none { it in host }
            }.distinct()

        private val ENGINE_HOSTS = listOf("google.", "gstatic.", "googleusercontent.", "qwant.", "bing.", "duckduckgo.", "brave.", "startpage.", "youtube.", "schema.org")

        /** Pages read per search: the engine's first two pages for two phrasings, and the learned sites. */
        private const val MAX_PAGES = 40
        private const val ENGINE_PAGES = 2
        private const val PARALLEL_PAGES = 6
        private const val PAGE_TIMEOUT_MS = 30_000L

        /** The hosts of every market with a crawler of its own: eBay in every country, Amazon in every country. */
        private val COVERED: Set<String> = PlatformId.entries.filter { it != PlatformId.WEB }
            .map { SiteRecipes.domainOf(it.baseUrl) }.filter { it.isNotEmpty() }.toSet() +
            setOf("ebay.com", "ebay.de", "ebay.at", "ebay.ch", "ebay.co.uk", "ebay.fr", "ebay.it", "ebay.es", "ebay.nl",
                "amazon.de", "amazon.com", "amazon.fr", "amazon.it", "amazon.es", "amazon.co.uk", "idealo.de", "geizhals.at", "geizhals.eu")

        /** Sites that sell nothing themselves: encyclopedias, video, forums, social networks. */
        private val SKIPPED = setOf(
            "wikipedia.org", "youtube.com", "reddit.com", "facebook.com", "instagram.com", "tiktok.com", "x.com",
            "twitter.com", "pinterest.com", "pinterest.de", "linkedin.com", "computerbase.de", "heise.de", "chip.de",
            "golem.de", "pcgameshardware.de", "tomshardware.com", "github.com", "stackoverflow.com", "quora.com",
        )
    }
}
