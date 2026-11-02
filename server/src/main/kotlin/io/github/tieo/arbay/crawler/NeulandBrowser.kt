package io.github.tieo.arbay.crawler

import kotlinx.coroutines.sync.Semaphore

/**
 * One of Neuland's browser pools, by its name there ("ebay", "search", "render"): Neuland keeps
 * the Chromes warm, gets past the checks a site puts before its pages, and reads them. Arbay sends
 * at most [tabs] pages at once, the pace the site lets through, and a crawl waiting its turn here
 * does not count as its market failing to answer.
 */
open class NeulandBrowser(
    private val name: String,
    /** Text only the site's error page carries. */
    private val errors: List<String> = emptyList(),
    /** Pages read at once. */
    tabs: Int = 1,
) {
    private val free = Semaphore(tabs)

    /** A page as the browser read it: [page] says whether it is the page asked for ("content"), the
     *  site's error page ("errorpage"), the site refusing the browser ("refused"), or neither. */
    class Page(val html: String, val url: String, val page: String)

    /**
     * Reads [url] until one of [markers] shows in it, the site's error page or one of [refusals]
     * does, or [timeoutS] passes. Null where Neuland did not answer.
     */
    suspend fun get(url: String, markers: List<String>, timeoutS: Int = 30, refusals: List<String> = emptyList()): Page? {
        val page = inTab { Neuland.read(name, url, markers, errors, refusals, timeoutS) } ?: return null
        // Any page the site answers with, its error page too, is the site answering: the crawl's time
        // limit is there for a crawl that hangs, and one eBay keeps refusing and asking again is not.
        kotlin.coroutines.coroutineContext[CrawlClock]?.answered()
        return page
    }

    /**
     * Where each of a site's redirects [urls] leads: the first address off [leaving] it reaches,
     * each given [timeoutS]. Those that led nowhere in time are left out.
     */
    suspend fun resolve(urls: List<String>, leaving: String, timeoutS: Int = 10): Map<String, String> =
        if (urls.isEmpty()) emptyMap() else inTab { Neuland.resolve(name, urls, leaving, timeoutS) }

    private suspend fun <T> inTab(work: suspend () -> T): T {
        val clock = kotlin.coroutines.coroutineContext[CrawlClock]
        if (clock != null) clock.waitingOnOthers { free.acquire() } else free.acquire()
        try {
            return work()
        } finally {
            free.release()
        }
    }
}

/**
 * The browser every eBay page is read through. eBay's edge serves its error page to every client
 * that does not look like a person's browser; a Chrome that keeps its profile and reads one page
 * after another is not one of them.
 */
object EbayBrowser : NeulandBrowser(
    "ebay", errors = listOf("went wrong"),
    // Five eBay sites, each search word's other spellings and every search running at once read
    // through it: with one tab, two saved searches of a dozen spellings each held all of the server's
    // search slots for twenty minutes.
    tabs = 3,
)

/**
 * The browser the search engines that refuse a plain request are asked through: Google answers only
 * browsers that run its scripts. Each web search asks five engines through it.
 */
object SearchBrowser : NeulandBrowser("search", tabs = 3)

/**
 * The browser shop pages are read through once their scripts ran, apart from [SearchBrowser], so a
 * shop page that hangs until its time runs out holds up no search engine.
 */
object RenderBrowsers : NeulandBrowser("render", tabs = 3)
