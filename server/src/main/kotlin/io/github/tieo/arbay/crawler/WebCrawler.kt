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
import kotlin.coroutines.coroutineContext
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
        val renders = java.util.concurrent.atomic.AtomicInteger(RENDERS_PER_SEARCH)
        val pages = coroutineScope {
            found.map { url -> async { gate.withPermit { withTimeoutOrNull(PAGE_TIMEOUT_MS) { read(url, words, renders) } ?: Page(Outcome.TIMED_OUT) } } }.awaitAll()
        }
        val outcomes = pages.groupingBy { it.outcome }.eachCount()
        log.info("Web: '{}' read {} pages: {} with offers, {} guessed, {} without, {} reported unreadable, {} blocked, {} timed out; {} offers",
            words, pages.size, outcomes[Outcome.OFFERS] ?: 0, outcomes[Outcome.GUESSED] ?: 0, outcomes[Outcome.NOTHING_SOLD] ?: 0, outcomes[Outcome.UNREADABLE] ?: 0,
            outcomes[Outcome.BLOCKED] ?: 0, outcomes[Outcome.TIMED_OUT] ?: 0, pages.sumOf { it.offers.size })
        return listingsOf(pages.flatMap { it.offers }, now)
    }

    /**
     * Reads again what was reported for [domain], now that it has a recipe: each reported page,
     * and the site's own search for the words that found it. The offers go into the stored
     * answers of those searches, so a search that passed the site before it was learned shows
     * them without being run again; a saved search's next run reads the site anyway. Answers the
     * offers found, by the words of the search they belong to.
     */
    suspend fun learned(domain: String): Map<String, List<Listing>> {
        val now = Clock.System.now()
        val byWords = SiteGaps.of(domain).groupBy({ it.second }, { it.first }).mapValues { (words, pages) ->
            val urls = (pages + SiteRecipes.all().filter { it.domain == domain }
                .mapNotNull { it.searchUrl?.replace("{q}", words.encodeUrl()) }).distinct()
            val found = listingsOf(urls.flatMap { read(it, words).offers }, now)
            QueryResultCache.extend(platformId, words, found)
            log.info("Web: {} learned, {} offers added to the search '{}'", domain, found.size, words)
            found
        }
        SiteGaps.learned.emit(SiteGaps.Learned(domain, byWords))
        return byWords
    }

    /** The listings of offers read off pages. A page pointing at an offer on a market Arbay
     *  crawls itself (a comparison site linking an eBay item) is that market's offer, which its
     *  own crawler reads better. */
    private fun listingsOf(offers: List<PageOffer>, now: kotlin.time.Instant): List<Listing> =
        offers.map { it.copy(url = withoutSession(behindAffiliateLink(it.url))) }.filter { it.inStock && !coveredElsewhere(it.url) }.map { o ->
            val domain = SiteRecipes.domainOf(o.url)
            Listing(
                id = "${platformId.name}:${idOf(o.url, o.title)}",
                platformId = platformId,
                externalId = idOf(o.url, o.title),
                url = o.url,
                title = o.title,
                price = o.price,
                saleType = if (o.rentPer != null) io.github.tieo.arbay.model.SaleType.RENT else null,
                rentPer = o.rentPer,
                rentRates = o.rentRates,
                condition = o.condition,
                imageUrls = listOfNotNull(o.image),
                seller = Seller(name = o.seller ?: domain, type = SellerType.BUSINESS, url = "https://$domain"),
                shipping = o.shipping?.let { Shipping(cost = it, free = it.amount == 0L) },
                gtin = o.gtin,
                mpn = o.mpn ?: ProductPages.modelInAddress(o.url, o.title),
                scrapedAt = now,
            )
        }.distinctBy { it.id }

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

    /** The shop's own address where a page links its offer through an affiliate network, which
     *  carries it as a parameter (awin's `ued`, others' `url` or `murl`). */
    private fun behindAffiliateLink(url: String): String {
        if (AD_HOSTS.none { it in SiteRecipes.domainOf(url) }) return url
        return listOf("ued", "murl", "url", "u", "p").firstNotNullOfOrNull { name ->
            Regex("""[?&]$name=([^&]+)""").find(url)?.groupValues?.get(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.startsWith("http") }
        } ?: url
    }

    /** An address without the session a shop writes into it (`;jsessionid=…`), which changes on
     *  every visit and would make the same offer a new one each time it is read. */
    private fun withoutSession(url: String): String = url.replace(SESSION_IN_PATH, "")

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

    private enum class Outcome { OFFERS, GUESSED, NOTHING_SOLD, UNREADABLE, BLOCKED, TIMED_OUT }
    private class Page(val outcome: Outcome, val offers: List<PageOffer> = emptyList())

    /** The offers on one page; a page that sells something and yields none is reported. */
    private suspend fun read(url: String, words: String, renders: java.util.concurrent.atomic.AtomicInteger? = null): Page {
        val domain = SiteRecipes.domainOf(url)
        val recipe = SiteRecipes.forUrl(url)
        val rendered = recipe?.render == true || RenderedSites.has(domain)
        var html = try {
            if (rendered) rendered(url) ?: throw CrawlerBlockedException("browser did not answer", ErrorType.TIMEOUT)
            else fetchWithFallback(client, url, "Web $domain")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.debug("Web: {} not fetched: {}", url, e.message?.take(80))
            return Page(Outcome.BLOCKED)
        }
        var offers = offersOn(recipe, html, url)
        // A shop passing itself off as another sells nothing it sends; its offers are bait.
        if (ProductPages.impersonates(html, url)) {
            log.warn("Web: {} passes itself off as another shop, left out", domain)
            return Page(Outcome.NOTHING_SOLD)
        }
        if (offers.isNotEmpty()) return Page(Outcome.OFFERS, offers)
        if (!ProductPages.sellsSomething(html)) return Page(Outcome.NOTHING_SOLD)
        // A shop whose offers its own scripts put on the page shows them only to a browser. Read
        // once more in one, a few pages per search since a browser is slow; a site that gives its
        // offers that way is remembered and opened in a browser from then on.
        if (!rendered && (renders == null || renders.getAndDecrement() > 0)) {
            rendered(url)?.let { page ->
                html = page
                offers = offersOn(recipe, page, url)
                if (offers.isNotEmpty()) {
                    RenderedSites.add(domain)
                    return Page(Outcome.OFFERS, offers)
                }
            }
        }
        // Reported either way, so the site gets its exact recipe; the guess only keeps this
        // search from missing the offer until then.
        SiteGaps.record(domain, url, words, html, recipeBroken = recipe != null)
        coroutineContext[UnreadableSites]?.sites?.add(domain)
        val guessed = runCatching { ProductPages.guess(html, url) }.getOrNull()
        return if (guessed != null) Page(Outcome.GUESSED, listOf(guessed)) else Page(Outcome.UNREADABLE)
    }

    private fun offersOn(recipe: SiteRecipe?, html: String, url: String): List<PageOffer> = try {
        SiteRecipes.asTheSiteDeals(url, recipe?.let { SiteRecipes.read(it, html, url) }.orEmpty().ifEmpty { ProductPages.offers(html, url) })
    } catch (e: Exception) {
        log.warn("Web: {} unreadable: {}", url, e.message?.take(80))
        emptyList()
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

        /**
         * A shop page as a person's Chrome shows it once its scripts ran: [SearchBrowser], warm and on
         * a display of its own, waiting until the page carries the marks shops put on their product
         * data, or [RENDER_TIMEOUT_S] passed. A headless Playwright waiting for the network to go
         * idle timed out on every MediaMarkt and Saturn page, whose trackers never stop.
         */
        internal suspend fun rendered(url: String): String? =
            SearchBrowser.get(url, SHOP_MARKERS, timeoutS = RENDER_TIMEOUT_S)?.html?.takeIf { it.isNotBlank() }

        private val SHOP_MARKERS = listOf("\"offers\"", "itemprop=\"price\"", "product:price:amount", "__NEXT_DATA__")
        private const val RENDER_TIMEOUT_S = 20

        private val SESSION_IN_PATH = Regex(""";(jsessionid|sid|phpsessid)=[^?#/]*""", RegexOption.IGNORE_CASE)

        /** Ad networks an engine's ads pass through on their way to the shop. */
        private val AD_HOSTS = listOf("admarketplace.", "doubleclick.", "googleadservices.", "adservice.", "awin1.", "tradedoubler.", "clickserve.", "ad.atdmt.", "partners.webmasterplan.", "pvn.", "tc.tradetracker.", "go.skimresources.")

        private val ENGINE_HOSTS = listOf("google.", "gstatic.", "googleusercontent.", "qwant.", "bing.", "duckduckgo.", "brave.", "startpage.", "youtube.", "schema.org")

        /** Pages read per search, of what the engines and the learned sites list. */
        private const val MAX_PAGES = 60
        private const val PARALLEL_PAGES = 8
        private const val PAGE_TIMEOUT_MS = 45_000L

        /** Pages read once more in a browser per search, for shops whose scripts write the offers. */
        private const val RENDERS_PER_SEARCH = 5

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
            // Mail portals, whose front pages carry ads with prices in them.
            "gmx.net", "gmx.de", "web.de", "t-online.de",
        )
    }
}

/**
 * The sites a web search found selling something and could not read, collected while it runs, so
 * the search can stay open for them: each is learned by the agent the gap feed reaches, and its
 * offers then join the search still on screen.
 */
class UnreadableSites : kotlin.coroutines.CoroutineContext.Element {
    val sites: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    companion object Key : kotlin.coroutines.CoroutineContext.Key<UnreadableSites>
    override val key: kotlin.coroutines.CoroutineContext.Key<*> = Key
}

/** The sites whose offers only a browser sees, found by reading them in one; opened in a browser
 *  from the first page on after that. Kept in the data directory. */
object RenderedSites {
    private val file = io.github.tieo.arbay.DataDir.file("web_rendered.json")
    private val sites: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
        runCatching { addAll(kotlinx.serialization.json.Json.decodeFromString<List<String>>(file.readText())) }
    }

    fun has(domain: String) = domain in sites

    fun add(domain: String) {
        if (sites.add(domain)) runCatching {
            file.writeText(kotlinx.serialization.json.Json.encodeToString(sites.sorted()))
        }
    }
}
