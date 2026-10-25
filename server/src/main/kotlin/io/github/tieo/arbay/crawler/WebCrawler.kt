package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.Seller
import io.github.tieo.arbay.model.SellerType
import io.github.tieo.arbay.model.Shipping
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import java.security.MessageDigest
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
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
class WebCrawler(private val client: HttpClient) : Crawler {
    override val platformId = PlatformId.WEB
    private val log = LoggerFactory.getLogger(WebCrawler::class.java)

    override suspend fun search(query: SearchQuery): List<Listing> {
        val words = query.positiveText
        // The engines' pages, then each learned site's own search: a site that has a recipe is
        // asked directly, so it is not left to whether an engine happens to list it.
        val listed = engineResults(words)
        if (listed.isEmpty()) throw CrawlerBlockedException("Web: no search engine answered", ErrorType.SERVICE_UNAVAILABLE_503)
        val found = (listed + siteSearches(words)).distinct()
            .filterNot { coveredElsewhere(it) }
            .take(MAX_PAGES)
        log.info("Web: '{}' reads {} pages", words, found.size)
        val now = Clock.System.now()
        val gate = Semaphore(PARALLEL_PAGES)
        val pages = coroutineScope {
            found.map { url -> async { gate.withPermit { withTimeoutOrNull(PAGE_TIMEOUT_MS) { read(url, words) } ?: Page(Outcome.TIMED_OUT) } } }.awaitAll()
        }
        val outcomes = pages.groupingBy { it.outcome }.eachCount()
        log.info("Web: '{}' read {} pages: {} with offers, {} without, {} reported unreadable, {} blocked, {} timed out; {} offers",
            words, pages.size, outcomes[Outcome.OFFERS] ?: 0, outcomes[Outcome.NOTHING_SOLD] ?: 0, outcomes[Outcome.UNREADABLE] ?: 0,
            outcomes[Outcome.BLOCKED] ?: 0, outcomes[Outcome.TIMED_OUT] ?: 0, pages.sumOf { it.offers.size })
        val offers = pages.flatMap { it.offers }
        // A page pointing at an offer on a market Arbay crawls itself (a comparison site linking
        // an eBay item) is that market's offer, which its own crawler reads better.
        return offers.filter { it.inStock && !coveredElsewhere(it.url) }.map { o ->
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

    /**
     * The pages every engine lists for the words. The engines that print their results as plain
     * HTML (Bing, DuckDuckGo) are read over HTTP, the others in [SearchBrowser]: from the server's
     * address Google and Qwant refuse anything that does not run their scripts, Brave throttles it
     * and Startpage answers it with nothing. A browser engine's pages come one after another, so
     * the plain ones are read meanwhile. An engine that lists nothing keeps its page as an error
     * snapshot, since that is how a changed results page shows.
     */
    private suspend fun engineResults(words: String): List<String> = coroutineScope {
        val plain = ENGINES.filter { !it.browser }.map { e -> async { results(e, words) } }
        val browser = ENGINES.filter { it.browser }.map { e -> results(e, words) }
        (plain.awaitAll() + browser).flatten().distinct()
    }

    private suspend fun results(engine: Engine, words: String): List<String> {
        val url = engine.url(words)
        val html = try {
            if (engine.browser) {
                val page = SearchBrowser.get(url, engine.markers, timeoutS = 25)
                    ?: throw CrawlerBlockedException("browser did not answer", ErrorType.TIMEOUT)
                if (page.page != "content") {
                    ErrorSnapshotStore.capture("web-${engine.name}", words, Exception("no results page (${page.page})"), ErrorType.PARSE_ERROR, url, html = page.html, finalUrl = page.url)
                    log.warn("Web: {} gave no results page for '{}' ({})", engine.name, words, page.page)
                    return emptyList()
                }
                page.html
            } else fetchWithFallback(client, url, "Web ${engine.name}")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.warn("Web: {} failed for '{}': {}", engine.name, words, e.message?.take(100))
            return emptyList()
        }
        // An ad goes through an ad network's redirect before the shop; it is followed like the
        // engine's own.
        val (ads, direct) = resultLinks(html, engine.host).partition { url -> AD_HOSTS.any { it in SiteRecipes.domainOf(url) } }
        val links = (direct + resolved(redirectsIn(html, engine) + ads, engine)).distinct()
        if (links.isEmpty()) {
            ErrorSnapshotStore.capture("web-${engine.name}", words, Exception("results page without result links"), ErrorType.PARSE_ERROR, url, html = html)
        }
        log.info("Web: {} listed {} pages for '{}'", engine.name, links.size, words)
        return links
    }

    /**
     * Where an engine's own redirects lead. Google's results link only to `/goto?url=<token>` and
     * its Shopping ads to `/aclk?...`, the token encrypted, so the address is what the redirect
     * answers; read without following it, one request per result, no browser needed.
     */
    private suspend fun resolved(redirects: List<String>, engine: Engine): List<String> = coroutineScope {
        val gate = Semaphore(PARALLEL_PAGES)
        redirects.map { start ->
            async {
                gate.withPermit {
                    var url = start
                    // An ad passes through the engine's ad server before the shop.
                    repeat(MAX_HOPS) {
                        val next = runCatching { noRedirects.get(url) { headers { append("User-Agent", USER_AGENT) } }.headers["Location"] }
                            .getOrNull() ?: return@withPermit null
                        url = ProductPages.absolute(next, url)
                        val host = SiteRecipes.domainOf(url)
                        if (engine.host !in host && ENGINE_HOSTS.none { it in host } && AD_HOSTS.none { it in host }) return@withPermit url
                    }
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** The links of a results page that are the engine's own redirects to a result. */
    private fun redirectsIn(html: String, engine: Engine): List<String> =
        if (engine.redirects.isEmpty()) emptyList()
        else org.jsoup.Jsoup.parse(html, engine.url("")).select("a[href]").map { it.absUrl("href") }
            .filter { href -> engine.redirects.any { it in href } }.distinct().take(MAX_REDIRECTS)

    private val noRedirects = HttpClient(io.ktor.client.engine.cio.CIO) {
        followRedirects = false
        engine { requestTimeout = 15_000 }
    }

    private fun siteSearches(words: String): List<String> =
        SiteRecipes.all().mapNotNull { r -> r.searchUrl?.replace("{q}", words.encodeUrl()) }.distinct()

    private enum class Outcome { OFFERS, NOTHING_SOLD, UNREADABLE, BLOCKED, TIMED_OUT }
    private class Page(val outcome: Outcome, val offers: List<PageOffer> = emptyList())

    /** The offers on one page; a page that sells something and yields none is reported. */
    private suspend fun read(url: String, words: String): Page {
        val domain = SiteRecipes.domainOf(url)
        val recipe = SiteRecipes.forUrl(url)
        val html = try {
            fetchWithFallback(client, url, "Web $domain", browserOnly = recipe?.render == true)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.debug("Web: {} not fetched: {}", url, e.message?.take(80))
            return Page(Outcome.BLOCKED)
        }
        val offers = try {
            recipe?.let { SiteRecipes.read(it, html, url) }.orEmpty().ifEmpty { ProductPages.offers(html, url) }
        } catch (e: Exception) {
            log.warn("Web: {} unreadable: {}", url, e.message?.take(80))
            emptyList()
        }
        if (offers.isNotEmpty()) return Page(Outcome.OFFERS, offers)
        if (!ProductPages.sellsSomething(html)) return Page(Outcome.NOTHING_SOLD)
        SiteGaps.record(domain, url, words, html, recipeBroken = recipe != null)
        return Page(Outcome.UNREADABLE)
    }

    /** Whether the page belongs to a market Arbay crawls by its own crawler, which reads it better. */
    private fun coveredElsewhere(url: String): Boolean {
        val host = SiteRecipes.domainOf(url)
        return host.isEmpty() || siteName(host) in COVERED ||
            SKIPPED.any { host == it || host.endsWith(".$it") } || url.substringBefore('?').lowercase().endsWith(".pdf")
    }

    private fun idOf(url: String, title: String): String =
        MessageDigest.getInstance("SHA-1").digest("$url|$title".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    /** A search engine: its results page for some words, whether only a browser gets it, and what
     *  shows that page arrived. */
    private class Engine(
        val name: String,
        val host: String,
        val browser: Boolean,
        val markers: List<String>,
        /** Paths of the engine's own redirects its results link to, where it hides the address. */
        val redirects: List<String> = emptyList(),
        val url: (String) -> String,
    )

    companion object {
        private val ENGINES = listOf(
            Engine("Bing", "bing.", false, emptyList()) { q -> "https://www.bing.com/search?q=${q.encodeUrl()}&setlang=de&cc=DE&count=30" },
            Engine("DuckDuckGo", "duckduckgo.", false, emptyList()) { q -> "https://html.duckduckgo.com/html/?q=${q.encodeUrl()}&kl=de-de" },
            Engine("Google", "google.", true, listOf("id=\"rso\"", "id=\"search\""), redirects = listOf("/goto?url=", "/aclk?")) { q ->
                "https://www.google.com/search?q=${q.encodeUrl()}&hl=de&gl=de&num=20"
            },
            Engine("Qwant", "qwant.", true, listOf("data-testid=\"webResult\"", "data-testid=\"serTitle\"")) { q ->
                "https://www.qwant.com/?q=${q.encodeUrl()}&t=web&locale=de_DE"
            },
            Engine("Brave", "brave.", true, listOf("id=\"results\"", "data-type=\"web\"")) { q ->
                "https://search.brave.com/search?q=${q.encodeUrl()}&source=web"
            },
            Engine("Startpage", "startpage.", true, listOf("class=\"w-gl", "class=\"result")) { q ->
                "https://www.startpage.com/do/search?q=${q.encodeUrl()}&language=deutsch"
            },
        )

        /** The pages a results page links to, apart from the engine's own and its redirects'. */
        internal fun resultLinks(html: String, engineHost: String): List<String> =
            org.jsoup.Jsoup.parse(html).select("a[href]").mapNotNull { a ->
                unwrap(a.attr("href"))?.takeIf { it.startsWith("http") }
            }.filter { url ->
                val host = SiteRecipes.domainOf(url)
                host.isNotEmpty() && engineHost !in host && ENGINE_HOSTS.none { it in host }
            }.distinct()

        /** The page behind an engine's own redirect: Google's `/url?q=`, DuckDuckGo's `/l/?uddg=`,
         *  Bing's `/ck/a?...&u=a1<base64 of the address>`. */
        private fun unwrap(href: String): String? {
            fun param(name: String) = Regex("""[?&]$name=([^&]+)""").find(href)?.groupValues?.get(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            return when {
                href.startsWith("/url?") -> param("q")
                "duckduckgo.com/l/" in href -> param("uddg")
                "bing.com/ck/a" in href -> param("u")?.removePrefix("a1")?.let { b ->
                    runCatching { String(java.util.Base64.getUrlDecoder().decode(b.padEnd((b.length + 3) / 4 * 4, '='))) }.getOrNull()
                }
                else -> href
            }
        }

        /** Ad networks an engine's ads pass through on their way to the shop. */
        private val AD_HOSTS = listOf("admarketplace.", "doubleclick.", "googleadservices.", "adservice.", "awin1.", "tradedoubler.", "clickserve.", "ad.atdmt.")

        private val ENGINE_HOSTS = listOf("google.", "gstatic.", "googleusercontent.", "qwant.", "bing.", "duckduckgo.", "brave.", "startpage.", "youtube.", "schema.org")

        /** Pages read per search, of what the engines and the learned sites list. */
        private const val MAX_PAGES = 60
        private const val PARALLEL_PAGES = 8
        private const val PAGE_TIMEOUT_MS = 30_000L

        /** Redirects resolved per results page, and hops followed per redirect. */
        private const val MAX_REDIRECTS = 30
        private const val MAX_HOPS = 4

        /** The names of the markets with a crawler of their own, in every country they run in:
         *  amazon.co.jp is Amazon as much as amazon.de is. */
        private val COVERED: Set<String> = PlatformId.entries.filter { it != PlatformId.WEB }
            .map { siteName(SiteRecipes.domainOf(it.baseUrl)) }.filter { it.isNotEmpty() }.toSet() +
            setOf("ebay", "amazon", "idealo", "geizhals")

        /** A host's name without its country or kind: "amazon" for www.amazon.co.jp. */
        internal fun siteName(host: String): String {
            val labels = host.split('.').filter { it.isNotEmpty() }
            if (labels.size < 2) return host
            // A two-part ending like co.uk or com.au is the country, and the name sits before it.
            val twoPart = labels.size >= 3 && labels[labels.size - 2] in setOf("co", "com", "org", "net", "ac", "gv", "ne", "or")
            return labels[labels.size - (if (twoPart) 3 else 2)]
        }

        /** Sites that sell nothing themselves: encyclopedias, video, forums, social networks. */
        private val SKIPPED = setOf(
            "wikipedia.org", "youtube.com", "reddit.com", "facebook.com", "instagram.com", "tiktok.com", "x.com",
            "twitter.com", "pinterest.com", "pinterest.de", "linkedin.com", "computerbase.de", "heise.de", "chip.de",
            "golem.de", "pcgameshardware.de", "tomshardware.com", "github.com", "stackoverflow.com", "quora.com",
        )
    }
}
