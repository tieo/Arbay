package io.github.tieo.arbay.crawler

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/**
 * The search engines Arbay asks itself (Bing, DuckDuckGo, Google, Qwant, Brave, Startpage): the
 * pages they list for some words, for the web market to read for offers ([links]), and their
 * results as a person reads them, title, address and snippet, for a search of the web on any
 * words ([serp]).
 */
class SearchEngines(private val client: HttpClient) {
    private val log = LoggerFactory.getLogger(SearchEngines::class.java)

    /**
     * The pages every engine lists for the words. The engines that print their results as plain
     * HTML (Bing, DuckDuckGo) are read over HTTP, the others in [SearchBrowser]: from the server's
     * address Google and Qwant refuse anything that does not run their scripts, Brave throttles it
     * and Startpage answers it with nothing. A browser engine's pages come one after another, so
     * the plain ones are read meanwhile. An engine that lists nothing keeps its page as an error
     * snapshot, since that is how a changed results page shows.
     */
    suspend fun links(words: String): List<String> = coroutineScope {
        val asked = ENGINES.filter { !resting(it) }
        // An engine's pages one after another, the next only after a page with results: Bing
        // answered two at once with a challenge for the first.
        val plain = asked.filter { !it.browser }.map { e ->
            async {
                val all = mutableListOf<String>()
                for (page in 1..e.pages) {
                    val found = results(e, words, page)
                    all += found
                    if (found.isEmpty()) break
                }
                all
            }
        }
        val browser = asked.filter { it.browser }.map { e -> results(e, words) }
        (plain.awaitAll() + browser).flatten().distinct()
    }

    private suspend fun results(engine: Engine, words: String, page: Int = 1): List<String> {
        val url = engine.pageUrl(words, page)
        val html = resultsPage(engine, words, page).getOrElse { return emptyList() }
        // An ad goes through an ad network's redirect before the shop; it is followed like the
        // engine's own.
        val (ads, direct) = resultLinks(html, engine.host).partition { url -> AD_HOSTS.any { it in SiteRecipes.domainOf(url) } }
        val links = (direct + resolved(redirectsIn(html, engine) + ads, engine)).distinct()
        if (links.isEmpty() && page == 1) {
            ErrorSnapshotStore.capture("web-${engine.name}", words, Exception("results page without result links"), ErrorType.PARSE_ERROR, url, html = html)
        }
        log.info("Web: {} listed {} pages for '{}'{}", engine.name, links.size, words, if (page > 1) " on its page $page" else "")
        return links
    }

    /** An engine's results page for the words, or why there is none. */
    private suspend fun resultsPage(engine: Engine, words: String, pageNo: Int = 1): Result<String> {
        val url = engine.pageUrl(words, pageNo)
        return try {
            if (engine.browser) {
                val page = SearchBrowser.get(url, engine.markers, timeoutS = 25, refusals = engine.refusals)
                    ?: throw CrawlerBlockedException("browser did not answer", ErrorType.TIMEOUT)
                // An engine that put a bot check before its results rests a while: asked again at
                // once it checks again, and each check held every search up for the browser's wait.
                if (page.page == "refused") refused[engine.name] = System.currentTimeMillis()
                if (page.page != "content") {
                    ErrorSnapshotStore.capture("web-${engine.name}", words, Exception("no results page (${page.page})"), ErrorType.PARSE_ERROR, url, html = page.html, finalUrl = page.url)
                    log.warn("Web: {} gave no results page for '{}' ({})", engine.name, words, page.page)
                    return Result.failure(Exception("no results page (${page.page})"))
                }
                Result.success(page.html)
            } else Result.success(fetchWithFallback(client, url, "Web ${engine.name}"))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.warn("Web: {} failed for '{}': {}", engine.name, words, e.message?.take(100))
            Result.failure(e)
        }
    }

    /**
     * What each engine lists for [words], as a person reads its results page: every result's title,
     * address and snippet in the engine's order, and all of them merged by address. [engines] picks
     * engines by name; none picks every one. The browser engines are asked one after another, as
     * for a search, the plain ones meanwhile.
     */
    suspend fun serp(words: String, engines: Set<String> = emptySet()): WebSearch = coroutineScope {
        val picked = ENGINES.filter { e -> engines.isEmpty() || engines.any { it.equals(e.name, ignoreCase = true) } }
        val resting = picked.filter { resting(it) }.map { EngineResults(it.name, "resting: it answered the server with a bot check", emptyList()) }
        suspend fun ask(e: Engine): EngineResults {
            val html = resultsPage(e, words).getOrElse { return EngineResults(e.name, "failed: ${it.message?.take(120)}", emptyList()) }
            // The last results page of each engine is kept, which is what a reader of a changed
            // page is mended against.
            runCatching { io.github.tieo.arbay.DataDir.file("serp_pages").apply { mkdirs() }.resolve("${e.name}.html").writeText(html) }
            val found = serpRows(html, e.host, e.url(""), e.redirects.filter { it != "/aclk?" })
            // Google hides each address behind its own redirect, resolved like a search's.
            val rows = found.mapNotNull { r ->
                val url = if (e.redirects.any { it in r.url }) resolved(listOf(r.url), e).firstOrNull() ?: return@mapNotNull null else r.url
                r.copy(url = WebCrawler.withoutSession(WebCrawler.unproxied(url)))
            }.distinctBy { it.url }.mapIndexed { i, r -> r.copy(rank = i + 1) }
            if (rows.isEmpty()) ErrorSnapshotStore.capture("web-serp-${e.name}", words, Exception("results page without results"), ErrorType.PARSE_ERROR, e.url(words), html = html)
            return EngineResults(e.name, if (rows.isEmpty()) "no results read" else "ok", rows)
        }
        val plain = picked.filter { !it.browser && !resting(it) }.map { e -> async { ask(e) } }
        val browser = picked.filter { it.browser && !resting(it) }.map { ask(it) }
        val all = plain.awaitAll() + browser + resting
        val merged = all.flatMap { e -> e.results.map { e.engine to it } }
            .groupBy { (_, r) -> r.url.removeSuffix("/") }
            .map { (_, hits) ->
                val (_, first) = hits.minBy { it.second.rank }
                WebResult(first.url, first.title, hits.map { it.second.snippet }.maxBy { it.length }, hits.map { it.first }.distinct(), hits.minOf { it.second.rank })
            }
            .sortedWith(compareByDescending<WebResult> { it.engines.size }.thenBy { it.bestRank })
        WebSearch(words, all, merged)
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

    /** When each engine last put a bot check before its results. */
    private val refused = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Whether [engine] checked the server for a bot recently, and is left out until it is likely
     *  to answer again. */
    private fun resting(engine: Engine): Boolean =
        refused[engine.name]?.let { System.currentTimeMillis() - it < REST_AFTER_A_CHECK_MS } == true

    /** A search engine: its results page for some words, whether only a browser gets it, and what
     *  shows that page arrived. */
    private class Engine(
        val name: String,
        val host: String,
        val browser: Boolean,
        val markers: List<String>,
        /** Paths of the engine's own redirects its results link to, where it hides the address. */
        val redirects: List<String> = emptyList(),
        /** Text of the bot check the engine puts before its results instead of answering. */
        val refusals: List<String> = emptyList(),
        /** Results pages a search reads, and the address of a page after the first. */
        val pages: Int = 1,
        val next: (String, Int) -> String = { url, _ -> url },
        val url: (String) -> String,
    ) {
        fun pageUrl(words: String, page: Int) = if (page <= 1) url(words) else next(url(words), page)
    }

    companion object {
        private val ENGINES = listOf(
            // Bing prints ten results a page whatever count asks for; the second page adds ten more
            // over plain HTTP, no browser needed.
            Engine("Bing", "bing.", false, emptyList(), pages = 2, next = { url, page -> "$url&first=${(page - 1) * 10 + 1}" }) { q ->
                "https://www.bing.com/search?q=${q.encodeUrl()}&setlang=de&cc=DE"
            },
            Engine("DuckDuckGo", "duckduckgo.", false, emptyList()) { q -> "https://html.duckduckgo.com/html/?q=${q.encodeUrl()}&kl=de-de" },
            Engine("Google", "google.", true, listOf("id=\"rso\"", "id=\"search\""), redirects = listOf("/goto?url=", "/aclk?")) { q ->
                "https://www.google.com/search?q=${q.encodeUrl()}&hl=de&gl=de&num=20"
            },
            Engine("Qwant", "qwant.", true, listOf("data-testid=\"webResult\"", "data-testid=\"serTitle\"")) { q ->
                "https://www.qwant.com/?q=${q.encodeUrl()}&t=web&locale=de_DE"
            },
            Engine("Brave", "brave.", true, listOf("id=\"results\"", "data-type=\"web\""), refusals = listOf("Verifying you're not a bot", "decided to schedule a captcha")) { q ->
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

        /**
         * The results of one engine's results page, in its order: each result's address, its title
         * and the text beside it. Read by shape rather than by each engine's classes, which change:
         * a result is the largest part of the page holding links to its one address and to no
         * other, its title the heading in it or else its longest link text, its snippet the rest
         * of its text. An address hidden behind one of [redirects] is kept as the redirect.
         */
        internal fun serpRows(html: String, engineHost: String, base: String, redirects: List<String>): List<SerpResult> {
            val doc = org.jsoup.Jsoup.parse(html, base)
            fun targetOf(a: org.jsoup.nodes.Element): String? {
                val abs = a.absUrl("href")
                if (redirects.any { it in abs }) return abs
                return unwrap(a.attr("href"))?.takeIf { it.startsWith("http") }?.takeIf { url ->
                    val host = SiteRecipes.domainOf(url)
                    host.isNotEmpty() && engineHost !in host && ENGINE_HOSTS.none { it in host } && AD_HOSTS.none { it in host }
                }
            }
            // The engine's own header, menus and footer link elsewhere too, and list no result.
            fun inFrame(a: org.jsoup.nodes.Element) = a.parents().any { p ->
                p.tagName() in setOf("footer", "nav", "header") || p.attr("role") in setOf("contentinfo", "navigation", "banner")
            }
            val target = LinkedHashMap<org.jsoup.nodes.Element, String>()
            for (a in doc.select("a[href]")) if (!inFrame(a)) target[a] = targetOf(a) ?: continue
            fun targetsIn(e: org.jsoup.nodes.Element) = e.select("a[href]").mapNotNullTo(HashSet()) { target[it] }
            fun headings(e: org.jsoup.nodes.Element) = e.select("h1, h2, h3, h4").size
            return target.entries.groupBy({ it.value }, { it.key }).mapNotNull { (url, anchors) ->
                // The result is the largest part of the page holding this address and no other, and
                // no heading beyond its own: a page listing one result holds it in a section that
                // has the page's own headings too.
                var block = anchors.first()
                while (true) {
                    val up = block.parent() ?: break
                    if (up.tagName() == "body" || targetsIn(up).size > 1 || headings(up) > maxOf(1, headings(block))) break
                    block = up
                }
                val heading = anchors.firstNotNullOfOrNull { it.selectFirst("h1, h2, h3, h4") } ?: block.selectFirst("h1, h2, h3, h4")
                // Without a heading, the title is the longest part of a link to it that is no address
                // (a site name, "example.com › path" and the title, in one link: Brave).
                val title = (heading?.text() ?: anchors.flatMap { it.select("*") }.map { it.text().trim() }
                    .filterNot { looksLikeAddress(it) }.maxByOrNull { it.length })
                    ?.trim()?.takeIf { it.length >= 2 } ?: return@mapNotNull null
                // The snippet is what is left of the result without its title, the address it prints,
                // the site's name and the engine's own links ("Anonymous view").
                val rest = block.clone()
                rest.select("h1, h2, h3, h4").filter { it.text().trim() == title }.forEach { it.remove() }
                rest.select("cite").remove()
                rest.select("a[href]").filter { a ->
                    val text = a.text().trim()
                    targetOf(a) == null || text == title || looksLikeAddress(text) || text.split(' ').size < 4 || title in text
                }.forEach { it.remove() }
                // Bing writes some of its snippets' symbols as entities twice over ("&#9742;"); Qwant
                // prints the address's path before the text ("laegler.com ‣ produkte ‣ hummel").
                val snippet = org.jsoup.parser.Parser.unescapeEntities(rest.text(), false)
                    .replace(Regex("""\s+"""), " ").trim().replace(LEADING_PATH, "").trim()
                SerpResult(0, title, url, snippet.take(SNIPPET_LENGTH))
            }.let { rows ->
                // An ad or a shopping card comes with a title and an address alone (Qwant's ads above
                // its results, Google's "Von Testberichte.de"); where results have snippets, one
                // without is one of those.
                if (rows.count { it.snippet.isNotEmpty() } >= 3) rows.filter { it.snippet.isNotEmpty() } else rows
            }.mapIndexed { i, r -> r.copy(rank = i + 1) }
        }

        private val LEADING_PATH = Regex("""^(\S+\s)?([›‣]\s*\S+\s*)+""")

        private const val SNIPPET_LENGTH = 500

        /** A link text that is the address it leads to, as engines print it ("example.com › path"). */
        private fun looksLikeAddress(text: String): Boolean {
            val t = text.trim()
            return t.startsWith("http") || "›" in t || "‣" in t || Regex("""^[\w-]+(\.[\w-]+)+(/\S*)?$""").matches(t)
        }

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
        internal val AD_HOSTS = listOf("admarketplace.", "doubleclick.", "googleadservices.", "adservice.", "awin1.", "tradedoubler.", "clickserve.", "ad.atdmt.", "partners.webmasterplan.", "pvn.", "tc.tradetracker.", "go.skimresources.")

        private val ENGINE_HOSTS = listOf("google.", "gstatic.", "googleusercontent.", "qwant.", "bing.", "duckduckgo.", "brave.", "startpage.", "youtube.", "schema.org",
            // Bing's footer: its terms and how it ranks.
            "go.microsoft.", "support.microsoft.",
            // Startpage's note on the ads it shows.
            "privacy.microsoft.")

        /** Redirects resolved per results page, and hops followed per redirect. */
        private const val MAX_REDIRECTS = 30
        private const val MAX_HOPS = 4

        private const val PARALLEL_PAGES = 8

        /** How long an engine that put a bot check before its results is left out. */
        private const val REST_AFTER_A_CHECK_MS = 60 * 60 * 1000L
    }
}

/** One result as an engine lists it: its place in the engine's list, from 1. */
@kotlinx.serialization.Serializable
data class SerpResult(val rank: Int, val title: String, val url: String, val snippet: String)

/** One engine's results for a search, or why it gave none. */
@kotlinx.serialization.Serializable
data class EngineResults(val engine: String, val status: String, val results: List<SerpResult>)

/** One address every engine that listed it agrees on, with the best place any gave it. */
@kotlinx.serialization.Serializable
data class WebResult(val url: String, val title: String, val snippet: String, val engines: List<String>, val bestRank: Int)

/** A web search's raw results: each engine's list, and all of them merged by address, the
 *  addresses most engines list first. */
@kotlinx.serialization.Serializable
data class WebSearch(val query: String, val engines: List<EngineResults>, val results: List<WebResult>)
