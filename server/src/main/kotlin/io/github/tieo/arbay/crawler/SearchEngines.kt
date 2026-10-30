package io.github.tieo.arbay.crawler

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/**
 * The search engines Arbay asks itself (Bing, DuckDuckGo, Google, Qwant, Brave, Startpage, Yandex,
 * Mojeek, Marginalia): the pages they list for some words, for the web market to read for offers
 * ([links]), and their results as a person reads them, title, address and snippet, for a search of
 * the web on any words ([serp]).
 */
class SearchEngines(private val client: HttpClient) {
    private val log = LoggerFactory.getLogger(SearchEngines::class.java)

    /**
     * The pages every engine lists for the words. The engines that print their results as plain
     * HTML (Bing, DuckDuckGo, Yandex's site search, Marginalia) are read over HTTP, the others in
     * [SearchBrowser]: from the server's address Google and Qwant refuse anything that does not run
     * their scripts, and Brave and Mojeek put a check first. Every engine is asked at once, the
     * browser ones in its tabs. An engine that lists nothing keeps its page as an error snapshot,
     * since that is how a changed results page shows.
     */
    suspend fun links(words: String): List<String> = coroutineScope {
        val asked = ENGINES.filter { it.forOffers && !resting(it) }
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
        val browser = asked.filter { it.browser }.map { e -> async { results(e, words) } }
        (plain.awaitAll() + browser.awaitAll()).flatten().distinct()
    }

    private suspend fun results(engine: Engine, words: String, page: Int = 1): List<String> {
        val url = engine.pageUrl(words, page)
        val html = matchedOnly(resultsPage(engine, words, page).getOrElse { return emptyList() }, engine)
        // An ad goes through an ad network's redirect before the shop; it is followed like the
        // engine's own.
        val (ads, direct) = resultLinks(html, engine.host).partition { url -> AD_HOSTS.any { it in SiteRecipes.domainOf(url) } }
        // A plain engine that answered a request with a check instead of results ("Noch ein letzter
        // Schritt", Bing now and then) is asked once more through the browser, which passes it.
        if (!engine.browser && direct.isEmpty() && ads.isEmpty()) {
            val page = SearchBrowser.get(url, engine.markers, timeoutS = 10)?.html
            if (page != null) return resultLinks(page, engine.host).filterNot { u -> AD_HOSTS.any { it in SiteRecipes.domainOf(u) } }
                .also { log.info("Web: {} listed {} pages for '{}' through the browser", engine.name, it.size, words) }
        }
        // The engine's result redirects are resolved as for a web search, the browser opening those
        // plain requests could not; its ad redirects lead on through ad networks, followed here.
        val results = if (engine.redirects.isEmpty()) emptyList()
            else resultLinks(withRedirectsResolved(html, engine), engine.host).filterNot { u -> AD_HOSTS.any { it in SiteRecipes.domainOf(u) } }
        val adRedirects = redirectsIn(html, engine).filter { "/aclk?" in it }
        // An engine that found nothing for the words may list something else instead (Yandex lists
        // popular pages for many rare words): its links are no pages for these words.
        if (unrelated(serpRows(html, engine.host, engine.url(""), engine.redirects), words)) {
            log.warn("Web: {} listed pages for '{}' that share no word with it", engine.name, words)
            return emptyList()
        }
        val links = (direct + results + resolved(adRedirects + ads, engine)).distinct()
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
                val page = SearchBrowser.get(url, engine.markers, timeoutS = 25, refusals = engine.refusals, tick = engine.tick)
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
            } else Result.success(afterWaiting(engine, fetchWithFallback(client, url, "Web ${engine.name}"), url))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            log.warn("Web: {} failed for '{}': {}", engine.name, words, e.message?.take(100))
            Result.failure(e)
        }
    }

    /** The results behind an engine's wait page, reached as the page asks: after its seconds, by
     *  its own link. Any other page as it is. */
    private suspend fun afterWaiting(engine: Engine, html: String, url: String): String {
        if (engine.goOn == null) return html
        val doc = org.jsoup.Jsoup.parse(html, url)
        val link = doc.selectFirst(engine.goOn)?.absUrl("href")?.takeIf { it.isNotEmpty() } ?: return html
        val seconds = engine.waitSeconds?.let { doc.selectFirst(it)?.attr("data-tr")?.toIntOrNull() } ?: 1
        log.info("Web: {} asked to wait {} s before its results", engine.name, seconds)
        kotlinx.coroutines.delay(seconds.coerceIn(1, 10) * 1000L)
        return fetchWithFallback(client, link, "Web ${engine.name}")
    }

    private suspend fun viaBrowser(engine: Engine, words: String): Result<String> =
        SearchBrowser.get(engine.url(words), engine.markers, timeoutS = 10)?.takeIf { it.page == "content" }?.html
            ?.let { Result.success(it) } ?: Result.failure(Exception("no results page through the browser either"))

    /**
     * What each engine lists for [words], as a person reads its results page: every result's title,
     * address and snippet in the engine's order, and all of them merged by address. [engines] picks
     * engines by name; none picks every one. Every engine is asked at once, as for a search.
     */
    suspend fun serp(words: String, engines: Set<String> = emptySet()): WebSearch = coroutineScope {
        val picked = ENGINES.filter { e -> engines.isEmpty() || engines.any { it.equals(e.name, ignoreCase = true) } }
        val resting = picked.filter { resting(it) }.map { EngineResults(it.name, "resting: it answered the server with a bot check", emptyList()) }
        suspend fun ask(e: Engine, again: Boolean = !e.browser): EngineResults {
            val page = (if (again || e.browser) resultsPage(e, words) else viaBrowser(e, words))
                .getOrElse { return EngineResults(e.name, if (again || e.browser) "failed: ${it.message?.take(120)}" else "no results read", emptyList()) }
            // The last results page of each engine is kept, which is what a reader of a changed
            // page is mended against.
            runCatching { io.github.tieo.arbay.DataDir.file("serp_pages").apply { mkdirs() }.resolve("${e.name}.html").writeText(page) }
            // Google hides each address behind a redirect of its own, a token different for every
            // link: a result's title and its "Diese Seite übersetzen" read as two addresses, which
            // split the result in two. The redirects are resolved first and read as the addresses
            // they lead to, the translation's to Google's translate host and so to no result.
            val html = matchedOnly(if (e.redirects.isEmpty()) page else withRedirectsResolved(page, e), e)
            val rows = serpRows(html, e.host, e.url(""), emptyList()).map { r ->
                r.copy(url = WebCrawler.withoutSession(WebCrawler.unproxied(r.url)))
            }.distinctBy { it.url }.mapIndexed { i, r -> r.copy(rank = i + 1) }
            // A plain engine that listed nothing is asked once more through the browser, as for a
            // search: Bing answers now and then with a check.
            if (rows.isEmpty() && again) return ask(e, again = false)
            if (unrelated(rows, words)) {
                log.warn("Web: {} listed {} results for '{}' that share no word with it", e.name, rows.size, words)
                return EngineResults(e.name, "results unrelated to the words, left out", emptyList())
            }
            if (rows.isEmpty()) ErrorSnapshotStore.capture("web-serp-${e.name}", words, Exception("results page without results"), ErrorType.PARSE_ERROR, e.url(words), html = html)
            return EngineResults(e.name, if (rows.isEmpty()) "no results read" else "ok", rows)
        }
        // The engines run on a scope of their own, so the search can answer at its deadline with
        // the engines done by then while the rest finish unseen: a read in the browser cannot be
        // cut short, and an MCP client gave up on a search started with a cold browser.
        val done = java.util.concurrent.ConcurrentHashMap<String, EngineResults>()
        val asked = picked.filter { !resting(it) }
        val runs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        val jobs = asked.map { e -> runs.launch { done[e.name] = ask(e) } }
        kotlinx.coroutines.withTimeoutOrNull(SERP_DEADLINE_MS) { jobs.joinAll() }
        val all = asked.map { e -> done[e.name] ?: EngineResults(e.name, "still searching after ${SERP_DEADLINE_MS / 1000} s, left out", emptyList()) } + resting
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
     * The pictures Bing Images finds for [words], each with the page it stands on, in Bing's order:
     * [pages] pages of 35. Its results page holds one picture until its script loads the rest from
     * `/images/async`, which answers plain HTTP, so that is what is read, page after page.
     */
    suspend fun images(words: String, pages: Int = IMAGE_PAGES): List<ImageResult> {
        val found = LinkedHashMap<String, ImageResult>()
        for (page in 0 until pages) {
            val url = "https://www.bing.com/images/async?q=${words.encodeUrl()}&first=${page * IMAGES_PER_PAGE}" +
                "&count=$IMAGES_PER_PAGE&mmasync=1&setlang=de&cc=DE"
            val html = try { fetchWithFallback(client, url, "Web Bing Images") } catch (e: CancellationException) { throw e } catch (e: Exception) {
                log.warn("Web: Bing Images failed for '{}': {}", words, e.message?.take(100)); break
            }
            val before = found.size
            for (r in imageRows(html)) found.putIfAbsent(r.image, r)
            if (found.size == before) break
        }
        return found.values.mapIndexed { i, r -> r.copy(rank = i + 1) }
    }

    /**
     * Where an engine's own redirects lead. Google's results link only to `/goto?url=<token>` and
     * its Shopping ads to `/aclk?...`, the token encrypted, so the address is what the redirect
     * answers; read without following it, one request per result, no browser needed.
     */
    private suspend fun resolved(redirects: List<String>, engine: Engine): List<String> =
        resolvedEach(redirects, engine).values.toList()

    /** [html] with every link to one of the engine's own redirects pointing where it leads, and one
     *  that leads nowhere readable pointing at the engine itself, which no result is. Ads stay out. */
    private suspend fun withRedirectsResolved(html: String, engine: Engine): String {
        val doc = org.jsoup.Jsoup.parse(html, engine.url(""))
        val links = doc.select("a[href]").filter { a -> engine.redirects.any { it != "/aclk?" && it in a.absUrl("href") } }
        // Google answers plain requests to its redirects with a wait page after the first few, and
        // its browser at once, so a browser engine's are opened there: only each result's title link,
        // the others ("Diese Seite übersetzen") leading to the same result or to Google's own pages.
        val to = if (engine.browser) {
            val titles = links.filter { it.selectFirst("h1, h2, h3, h4") != null }.map { it.absUrl("href") }.distinct().take(MAX_REDIRECTS)
            SearchBrowser.resolve(titles, engine.host, timeoutS = REDIRECT_TIMEOUT_S)
                .also { log.info("Web: {} redirects: {} of {} result titles resolved in the browser", engine.name, it.size, titles.size) }
        } else resolvedEach(links.map { it.absUrl("href") }.distinct().take(MAX_REDIRECTS * 2), engine)
        for (a in links) a.attr("href", to[a.absUrl("href")] ?: engine.url(""))
        return doc.outerHtml()
    }

    /** Where each of [redirects] leads, for those that lead to a page off the engine. */
    private suspend fun resolvedEach(redirects: List<String>, engine: Engine): Map<String, String> = coroutineScope {
        val gate = Semaphore(PARALLEL_PAGES)
        redirects.map { start ->
            async {
                start to
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
        }.awaitAll().mapNotNull { (start, url) -> url?.let { start to it } }.toMap()
    }

    /** [html] without the results the engine marked none of the searched words in. */
    private fun matchedOnly(html: String, engine: Engine): String {
        if (engine.item == null || engine.matched == null) return html
        val (kept, dropped) = withoutUnmatched(html, engine.url(""), engine.item, engine.matched)
        if (dropped > 0) log.info("Web: {} marked no searched word in {} of its results, left out", engine.name, dropped)
        return kept
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
        /** The "I'm not a robot" box of a check that passes on a click, by CSS selector. */
        val tick: List<String> = emptyList(),
        /** A result ([item]) and the mark the engine puts on the searched words it matched in it
         *  ([matched]): a result without one is not for the words, and is left out. */
        val item: String? = null,
        val matched: String? = null,
        /** A page the engine puts first when busy, asking to wait a few seconds ([waitSeconds], an
         *  element's `data-tr`) and go on by a link of its own ([goOn]). */
        val goOn: String? = null,
        val waitSeconds: String? = null,
        /** Results pages a search reads, and the address of a page after the first. */
        val pages: Int = 1,
        val next: (String, Int) -> String = { url, _ -> url },
        /** Whether a search for offers reads what it lists: an index that ranks shops down on
         *  purpose lists none worth a page of the search's budget. */
        val forOffers: Boolean = true,
        val url: (String) -> String,
    ) {
        fun pageUrl(words: String, page: Int) = if (page <= 1) url(words) else next(url(words), page)
    }

    companion object {
        private val ENGINES = listOf(
            // Bing prints ten results a page whatever count asks for; the second page adds ten more
            // over plain HTTP, no browser needed.
            Engine("Bing", "bing.", false, listOf("class=\"b_algo"), pages = 2, next = { url, page -> "$url&first=${(page - 1) * 10 + 1}" }) { q ->
                "https://www.bing.com/search?q=${q.encodeUrl()}&setlang=de&cc=DE"
            },
            Engine("DuckDuckGo", "duckduckgo.", false, listOf("result__a")) { q -> "https://html.duckduckgo.com/html/?q=${q.encodeUrl()}&kl=de-de" },
            Engine("Google", "google.", true, listOf("id=\"rso\"", "id=\"search\""), redirects = listOf("/goto?url=", "/aclk?")) { q ->
                "https://www.google.com/search?q=${q.encodeUrl()}&hl=de&gl=de&num=20"
            },
            Engine("Qwant", "qwant.", true, listOf("data-testid=\"webResult\"", "data-testid=\"serTitle\"")) { q ->
                "https://www.qwant.com/?q=${q.encodeUrl()}&t=web&locale=de_DE"
            },
            Engine("Brave", "brave.", true, listOf("id=\"results\"", "data-type=\"web\""), refusals = listOf("Verifying you're not a bot", "decided to schedule a captcha"),
                // Its check passes on its "Verify" button, the one marked busy while it checks.
                tick = listOf("button[aria-busy]")) { q ->
                "https://search.brave.com/search?q=${q.encodeUrl()}&source=web"
            },
            // Its "language" keeps only pages in that language, one result of eleven for English
            // words, and a browser keeps the last one asked for: "all" asks for every language,
            // "lui" sets only the language it speaks.
            Engine("Startpage", "startpage.", true, listOf("class=\"w-gl", "class=\"result")) { q ->
                "https://www.startpage.com/do/search?q=${q.encodeUrl()}&lui=deutsch&language=all"
            },
            // Three indexes of their own, beside the ones built on Bing's and Google's. Mojeek puts an
            // ALTCHA before the server's requests, plain or in a browser: the browser ticks its box,
            // and an engine that asks more after it rests like Brave on its check.
            // Yandex's own search puts a picture puzzle before the server's requests, the browser's
            // too. Its search for a site's own pages, given no site ("web=1"), searches its whole
            // index over plain HTTP with none; lr=177 asks it as from Berlin.
            // For words it finds nothing for, it fills the page with the same popular pages (Roblox's
            // sign-in, 1C, Husqvarna), none with a searched word set in bold as its results have.
            Engine("Yandex", "yandex.", false, listOf("b-serp-item__title-link"), item = ".b-serp-item", matched = "b:not([class])") { q ->
                "https://yandex.ru/search/site/?searchid=2313451&web=1&lr=177&text=${q.encodeUrl()}"
            },
            Engine("Mojeek", "mojeek.", true, listOf("class=\"results-standard\"", "class=\"title\""), refusals = listOf("--altcha-border-color"), tick = listOf("altcha-widget input[type=checkbox]", "#altcha-widget input")) { q ->
                "https://www.mojeek.com/search?q=${q.encodeUrl()}"
            },
            // Marginalia indexes the small, old and non-commercial web and ranks shops down by
            // design: forums, tools and archived pages a general engine buries.
            // When it sees much bot traffic it first answers "Wait A Moment" for a second, with a
            // link to go on by for a client without scripts.
            Engine("Marginalia", "marginalia", false, listOf("hyphens-auto"), forOffers = false,
                goOn = "noscript a[href*=sst=]", waitSeconds = "#countdown") { q ->
                "https://marginalia-search.com/search?query=${q.encodeUrl()}"
            },
        )

        /** The pictures of a Bing Images page: each link to a picture carries it as JSON in its `m`
         *  attribute, the picture's address (`murl`), its page's (`purl`), a thumbnail's and a title. */
        internal fun imageRows(html: String): List<ImageResult> =
            org.jsoup.Jsoup.parse(html).select("a.iusc[m]").mapNotNull { a ->
                val m = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(a.attr("m")) as? kotlinx.serialization.json.JsonObject }.getOrNull() ?: return@mapNotNull null
                fun field(name: String) = (m[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                val image = field("murl")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val page = field("purl")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                // The picture's size and type stand beside it as "1500 x 1500 · jpeg".
                val size = a.parents().firstOrNull { it.selectFirst(".img_info") != null }?.selectFirst(".img_info span.nowrap")?.text()?.trim()
                ImageResult(0, field("t").orEmpty(), image, page, field("turl"), size?.takeIf { it.isNotBlank() })
            }

        /**
         * Whether an engine's results are for other words than [words]: fewer than a third of them
         * name any of its words in their title, text or address. Yandex answers many rare words
         * with popular pages (Roblox's sign-in, a bank), counted as results all the same.
         */
        internal fun unrelated(rows: List<SerpResult>, words: String): Boolean {
            val wanted = words.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length >= 3 }.toSet()
            if (rows.isEmpty() || wanted.isEmpty()) return false
            val sharing = rows.count { r -> "${r.title} ${r.snippet} ${r.url}".lowercase().let { text -> wanted.any { it in text } } }
            return sharing * 3 < rows.size
        }

        /** A results page without its results ([item]) holding no [matched] mark, and how many
         *  those were. */
        internal fun withoutUnmatched(html: String, base: String, item: String, matched: String): Pair<String, Int> {
            val doc = org.jsoup.Jsoup.parse(html, base)
            val unmatched = doc.select(item).filter { it.selectFirst(matched) == null }
            if (unmatched.isEmpty()) return html to 0
            unmatched.forEach { it.remove() }
            return doc.outerHtml() to unmatched.size
        }

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
            // The engine's own header, menus, side panels and footer link elsewhere too, and list no
            // result, nor does a dialog the page keeps hidden (Marginalia's language menu) or an inert
            // part (Bing's AI answer, whose source links read as results under its own words). Being
            // aria-hidden says nothing: Google's results are, behind its consent dialog.
            fun inFrame(a: org.jsoup.nodes.Element) = a.parents().any { p ->
                p.tagName() in setOf("footer", "nav", "header", "aside") || p.attr("role") in setOf("contentinfo", "navigation", "banner", "dialog") ||
                    p.hasAttr("hidden") || p.hasAttr("inert")
            }
            val target = LinkedHashMap<org.jsoup.nodes.Element, String>()
            // A link that opens a popup rather than the page (role "button": Bing's AI answer cites
            // its sources so) is no result.
            for (a in doc.select("a[href]")) if (!inFrame(a) && a.attr("role") != "button") target[a] = targetOf(a) ?: continue
            fun targetsIn(e: org.jsoup.nodes.Element) = e.select("a[href]").mapNotNullTo(HashSet()) { target[it] }
            fun headings(e: org.jsoup.nodes.Element) = e.select("h1, h2, h3, h4").size
            val place = target.keys.withIndex().associate { (i, a) -> a to i }
            return target.entries.groupBy({ it.value }, { it.key }).mapNotNull { (url, anchors) ->
                // The result is the largest part of the page holding this address and no other, and
                // no heading beyond its own: a page listing one result holds it in a section that
                // has the page's own headings too.
                // An address linked from several places (Bing's AI answer lists its sources as
                // cards above the results) is read where a heading links it, the result itself.
                fun inHeading(a: org.jsoup.nodes.Element) =
                    a.selectFirst("h1, h2, h3, h4") != null || a.parents().any { it.tagName() in setOf("h1", "h2", "h3", "h4") }
                val start = anchors.firstOrNull(::inHeading) ?: anchors.first()
                var block = start
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
                    // Yandex numbers its results in their text ("1. Die Bandschleifmaschine …").
                    .replace(LIST_NUMBER, "")
                place.getValue(start) to SerpResult(0, title, url, snippet.take(SNIPPET_LENGTH))
            }.sortedBy { it.first }.map { it.second }.let { rows ->
                // An ad or a shopping card comes with a title and an address alone (Qwant's ads above
                // its results, Google's "Von Testberichte.de"); where results have snippets, one
                // without is one of those.
                if (rows.count { it.snippet.isNotEmpty() } >= 3) rows.filter { it.snippet.isNotEmpty() } else rows
            }.mapIndexed { i, r -> r.copy(rank = i + 1) }
        }

        private val LEADING_PATH = Regex("""^(\S+\s)?([›‣]\s*\S+\s*)+""")

        private val LIST_NUMBER = Regex("""^\d{1,3}\.\s+""")

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

        private val ENGINE_HOSTS = listOf("google.", "translate.goog", "gstatic.", "googleusercontent.", "qwant.", "bing.", "duckduckgo.", "brave.", "startpage.", "yandex.", "ya.ru", "mojeek.", "marginalia", "youtube.", "schema.org",
            // Bing's footer: its terms and how it ranks.
            "go.microsoft.", "support.microsoft.",
            // Startpage's note on the ads it shows, and Mojeek's survey above its results.
            "privacy.microsoft.", "blocksurvey.")

        /** Redirects resolved per results page, and hops followed per redirect. */
        private const val MAX_REDIRECTS = 30
        private const val MAX_HOPS = 4
        /** How long a redirect opened in the browser is given to leave the engine: Google's took up
         *  to 9 s when asked many times in a row. */
        private const val REDIRECT_TIMEOUT_S = 15

        private const val PARALLEL_PAGES = 8

        /** Bing Images' pages read for one image search, and the pictures on each. */
        private const val IMAGE_PAGES = 3
        private const val IMAGES_PER_PAGE = 35

        /** When a search of the web answers, with the engines done by then. */
        private const val SERP_DEADLINE_MS = 50_000L

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

/** One picture an image search found: its place in the engine's list from 1, its title, the
 *  picture's address, the page it stands on, a thumbnail, and its size as the engine states it. */
@kotlinx.serialization.Serializable
data class ImageResult(val rank: Int, val title: String, val image: String, val page: String, val thumbnail: String? = null, val size: String? = null)
