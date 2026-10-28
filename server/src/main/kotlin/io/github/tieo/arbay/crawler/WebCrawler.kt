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
import io.ktor.client.statement.bodyAsText
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

    /** The search engines whose results pages a search reads for shops. */
    internal val engines = SearchEngines(client)
    private val log = LoggerFactory.getLogger(WebCrawler::class.java)

    override suspend fun search(query: SearchQuery): List<Listing> {
        val words = query.positiveText
        // The engines' pages and each learned site's own search: a site that has a recipe is
        // asked directly, so it is not left to whether an engine happens to list it.
        val listed = engines.links(words)
        if (listed.isEmpty()) throw CrawlerBlockedException("Web: no search engine answered", ErrorType.SERVICE_UNAVAILABLE_503)
        // Learned sites' own searches first: read by their exact recipes they win over the same
        // offer an engine's page gives less exactly (gerach's card states its rate from three days
        // on, its API one day's), and the page limit never cuts them.
        val found = (siteSearches(words) + listed.map { withoutSession(unproxied(it)) }).distinct()
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
        offers.map { it.copy(url = withoutSession(unproxied(behindAffiliateLink(it.url)))) }.filter { it.inStock && !coveredElsewhere(it.url) }.map { o ->
            val domain = SiteRecipes.domainOf(o.url)
            Listing(
                id = "${platformId.name}:${idOf(o.url, o.title)}",
                platformId = platformId,
                externalId = idOf(o.url, o.title),
                url = o.url,
                title = o.title,
                // The code price is what the offer costs; the price without the code stays beside it.
                price = o.coupon?.price ?: o.price,
                oldPrice = o.price.takeIf { o.coupon != null },
                coupon = o.coupon,
                saleType = if (o.rentPer != null || hiredOut(o)) io.github.tieo.arbay.model.SaleType.RENT else null,
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


    /** The shop's own address where a page links its offer through an affiliate network, which
     *  carries it as a parameter (awin's `ued`, others' `url` or `murl`). */
    private fun behindAffiliateLink(url: String): String {
        if (SearchEngines.AD_HOSTS.none { it in SiteRecipes.domainOf(url) }) return url
        return listOf("ued", "murl", "url", "u", "p").firstNotNullOfOrNull { name ->
            Regex("""[?&]$name=([^&]+)""").find(url)?.groupValues?.get(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.startsWith("http") }
        } ?: url
    }

    /** Whether an offer read off structured data says it is hired out, in its own address or
     *  title (maschinen-humpert.de/vermietung/…-mieten/, "Parkett Eckenschleifer zur Miete"), or
     *  comes from a site named for hiring out: its price is a rate, whatever period it is for. */
    private fun hiredOut(o: PageOffer): Boolean =
        Rentals.rentalSite(siteName(SiteRecipes.domainOf(o.url))) || Rentals.hiredOut(o.url.substringBefore('?') + " " + o.title)


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
            if (recipe?.items != null) apiAnswer(client, url)
            else if (rendered) rendered(url) ?: throw CrawlerBlockedException("browser did not answer", ErrorType.TIMEOUT)
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
        // A site's own search API answering no offer its recipe reads has nothing priced for the
        // words: Boels lists its toilet containers for "Dusch-WC" with no rate and a banner by the
        // week only. Its answer is data, never a page a browser would show more of.
        if (recipe?.items != null) return Page(Outcome.NOTHING_SOLD)
        // A shop's front page shows what it pushes today, not what was searched for; where the
        // site's own search is known, that search answers the words. grills.de's front page, listed
        // for "Weber Grill", holds no product tile.
        if (isFrontPage(url) && SiteRecipes.all().any { it.domain == domain && it.searchUrl != null }) return Page(Outcome.NOTHING_SOLD)
        if (SiteGaps.isGivenUp(domain) || !ProductPages.sellsSomething(html)) return Page(Outcome.NOTHING_SOLD)
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
        SiteRecipes.asTheSiteDeals(url, html, (if (recipe == null) emptyList() else SiteRecipes.readAt(url, html)).ifEmpty { SiteRecipes.withoutOffersReadElsewhere(url, ProductPages.offers(html, url)) })
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


    companion object {

        /**
         * A shop page as a person's Chrome shows it once its scripts ran: [SearchBrowser], warm and on
         * a display of its own, waiting until the page carries the marks shops put on their product
         * data, or [RENDER_TIMEOUT_S] passed. A headless Playwright waiting for the network to go
         * idle timed out on every MediaMarkt and Saturn page, whose trackers never stop.
         */
        internal suspend fun rendered(url: String): String? =
            SearchBrowser.get(url, SHOP_MARKERS, timeoutS = RENDER_TIMEOUT_S)?.html?.takeIf { it.isNotBlank() }

        /** A site's own API, asked for JSON: SAP Commerce's answers a browser's Accept header in XML. */
        internal suspend fun apiAnswer(client: HttpClient, url: String): String {
            val response = client.get(url) {
                headers {
                    append("User-Agent", USER_AGENT)
                    append("Accept", "application/json")
                    append("Accept-Language", "de-DE,de;q=0.9")
                }
            }
            if (response.status.value >= 400) throw CrawlerBlockedException("API answered ${response.status}", ErrorType.SERVICE_UNAVAILABLE_503)
            return response.bodyAsText()
        }

        private val SHOP_MARKERS = listOf("\"offers\"", "itemprop=\"price\"", "product:price:amount", "__NEXT_DATA__")
        private const val RENDER_TIMEOUT_S = 20

        /** An address without the session a shop writes into it (`;jsessionid=…`), which changes on
         *  every visit and would make the same offer a new one each time it is read. The tracking
         *  an engine adds (`utm_source=qwant`) goes the same way. */
        internal fun withoutSession(url: String): String = url.replace(SESSION_IN_PATH, "")
            .replace(TRACKING_PARAM, "").replace(Regex("""[?&]$"""), "").replace("?&", "?")

        /** Whether [url] is a site's front page: no path beyond "/", no query. */
        internal fun isFrontPage(url: String): Boolean =
            runCatching { java.net.URI(url) }.getOrNull()?.let { u -> u.rawPath.orEmpty().trim('/').isEmpty() && u.rawQuery == null } ?: false

        /** The shop's own address of a page an engine lists through Google's translating proxy
         *  (www-amazon-it.translate.goog/…?_x_tr_sl=it for www.amazon.it/…): the proxy's host names
         *  no shop, so the page would be read as a site of its own, and Amazon's not left to the
         *  crawler that reads it. The proxy writes the host's dots as dashes and its dashes doubled. */
        internal fun unproxied(url: String): String {
            val m = TRANSLATE_PROXY.find(url) ?: return url
            val host = m.groupValues[2].replace("--", "\u0000").replace('-', '.').replace('\u0000', '-')
            return withoutSession(m.groupValues[1] + host + m.groupValues[3])
        }

        private val TRANSLATE_PROXY = Regex("""^(https?://)([a-z0-9-]+)\.translate\.goog(.*)$""", RegexOption.IGNORE_CASE)

        /** What an engine or an ad adds to an address to count the visit, which makes the same
         *  offer a new one for every engine that listed it. */
        private val TRACKING_PARAM = Regex("""(?<=[?&])(utm_[a-z]+|gclid|gbraid|wbraid|fbclid|msclkid|gad_source|gad_campaignid|srsltid|lnid|campaign_type|msockid|_x_tr_[a-z_]+|zenid|oscsid|phpsessid)=[^&#]*&?""", RegexOption.IGNORE_CASE)

        private val SESSION_IN_PATH = Regex(""";(jsessionid|sid|phpsessid)=[^?#/]*""", RegexOption.IGNORE_CASE)



        /** Pages read per search, of what the engines and the learned sites list. */
        private const val MAX_PAGES = 60
        private const val PARALLEL_PAGES = 8
        private const val PAGE_TIMEOUT_MS = 45_000L

        /** Pages read once more in a browser per search, for shops whose scripts write the offers. */
        private const val RENDERS_PER_SEARCH = 5


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
            // News and their readers' forums, which name prices in stories and comments.
            "tagesschau.de", "spiegel.de", "zeit.de", "faz.net", "sueddeutsche.de", "welt.de", "bild.de", "focus.de",
            "n-tv.de", "stern.de", "merkur.de", "tz.de", "rnd.de", "br.de", "wdr.de", "ndr.de", "swr.de", "mdr.de", "zdf.de", "tagesspiegel.de",
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
