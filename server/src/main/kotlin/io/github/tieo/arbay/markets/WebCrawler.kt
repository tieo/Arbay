package io.github.tieo.arbay.markets

import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SearchQuery
import io.github.tieo.arbay.model.Seller
import io.github.tieo.arbay.model.SellerType
import io.github.tieo.arbay.model.Shipping
import java.security.MessageDigest
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import io.github.tieo.arbay.relevance.Rentals

/**
 * Every shop on the web, beyond the markets Arbay has a crawler of its own for.
 *
 * The words go to the search engines ([SearchEngines]) and to the own search of every site a
 * recipe has been written for ([SiteRecipes]). Neuland reads each page found for its offers, by the
 * site's recipe or the structured data shops publish for search engines. A page that sells
 * something and yields nothing is reported to [SiteGaps], whose reader writes the site a recipe, so
 * the next search reads it. What the pages hold beyond the thing searched for is left to the relevance
 * filter, as on every other market.
 */
class WebCrawler : Crawler {
    override val platformId = PlatformId.WEB

    /** The search engines whose results pages a search reads for shops. */
    internal val engines = SearchEngines()
    private val log = LoggerFactory.getLogger(WebCrawler::class.java)

    override suspend fun search(query: SearchQuery): List<Listing> {
        val words = query.positiveText
        // The engines' pages and each learned site's own search: a site that has a recipe is
        // asked directly, so it is not left to whether an engine happens to list it.
        val listed = engines.links(words)
        if (listed.isEmpty()) throw CrawlerBlockedException("Web: no search engine answered", ErrorType.SERVICE_UNAVAILABLE_503)
        // Learned sites' own searches first: read by their exact recipes they win over the same
        // offer an engine's page gives less exactly (gerach's card states its rate from three days
        // on, its API one day's). The page limit is the engines' alone, so the learned sites never
        // push out a shop an engine found: seventeen of them left 43 of 60 pages to the engines.
        val searches = siteSearches(words)
        val found = searches + listed.map { withoutSession(unproxied(it)) }.distinct()
            .filterNot { it in searches || coveredElsewhere(it) }
            .take(MAX_PAGES)
        log.info("Web: '{}' reads {} pages", words, found.size)
        val now = Clock.System.now()
        val gate = Semaphore(PARALLEL_PAGES)
        val shownOnce = ShopOnce()
        val renders = java.util.concurrent.atomic.AtomicInteger(RENDERS_PER_SEARCH)
        val pages = coroutineScope {
            found.map { url ->
                async {
                    // Timed like a market's crawl: waiting for the shared browser behind another
                    // page is not this page failing, and counted it cancelled pages mid-read, which
                    // got the browser restarted for every page queued behind them.
                    gate.withPermit { withCrawlLimitOrNull(PAGE_TIMEOUT_MS) { read(url, words, renders) } ?: Page(Outcome.TIMED_OUT) }
                        // Each page's offers show as it is read: the slowest page of sixty is often
                        // a minute behind the rest.
                        .also { emitPartialResults(shownOnce.keep(listingsOf(it.offers, now))) }
                }
            }.awaitAll()
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
    private fun listingsOf(offers: List<ShopOffer>, now: kotlin.time.Instant): List<Listing> =
        offers.map { it.copy(url = withoutSession(unproxied(behindAffiliateLink(it.url)))) }.filter { it.inStock && !coveredElsewhere(it.url) }.mapNotNull { o ->
            val domain = SiteRecipes.domainOf(o.url)
            val price = o.price.money() ?: return@mapNotNull null
            val coupon = o.coupon?.let { c -> c.price.money()?.let { io.github.tieo.arbay.model.Coupon(it, c.code, c.terms) } }
            val rentPer = o.rentPer?.let(::rentPeriod)
            Listing(
                id = "${platformId.name}:${idOf(o.url, o.title)}",
                platformId = platformId,
                externalId = idOf(o.url, o.title),
                url = o.url,
                title = o.title,
                // The code price is what the offer costs; the price without the code stays beside it.
                price = coupon?.price ?: price,
                oldPrice = price.takeIf { coupon != null },
                coupon = coupon,
                saleType = if (rentPer != null || hiredOut(o)) io.github.tieo.arbay.model.SaleType.RENT else null,
                rentPer = rentPer,
                rentRates = o.rentRates.mapNotNull { r -> rentPeriod(r.period)?.let { p -> r.price.money()?.let { io.github.tieo.arbay.model.RentRate(p, it) } } },
                condition = o.condition?.let(::condition),
                imageUrls = listOfNotNull(o.image),
                seller = Seller(name = o.seller ?: domain, type = SellerType.BUSINESS, url = "https://$domain"),
                shipping = o.shipping?.money()?.let { Shipping(cost = it, free = it.amount == 0L) },
                delivery = o.delivery,
                gtin = o.gtin,
                mpn = o.mpn,
                scrapedAt = now,
            )
        }.distinctBy { it.id }.let(::oneShopOnce)

    private fun rentPeriod(kebab: String) = io.github.tieo.arbay.model.RentPeriod.entries.firstOrNull { it.name == kebab.uppercase().replace('-', '_') }
    private fun condition(kebab: String) =
        if (kebab == "parts") io.github.tieo.arbay.model.Condition.PARTS_ONLY else io.github.tieo.arbay.model.Condition.entries.firstOrNull { it.name == kebab.uppercase().replace('-', '_') }

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
    private fun hiredOut(o: ShopOffer): Boolean =
        Rentals.rentalSite(siteName(SiteRecipes.domainOf(o.url))) || Rentals.hiredOut(o.url.substringBefore('?') + " " + o.title)


    private suspend fun siteSearches(words: String): List<String> =
        SiteRecipes.all().mapNotNull { r -> r.searchUrl?.replace("{q}", words.encodeUrl()) }.distinct()

    private enum class Outcome { OFFERS, GUESSED, NOTHING_SOLD, UNREADABLE, BLOCKED, TIMED_OUT }
    private class Page(val outcome: Outcome, val offers: List<ShopOffer> = emptyList())

    /**
     * The offers on one page, read by Neuland; a page that sells something and yields none is
     * reported. A few pages per search may be read once more in a browser, which is slow, for shops
     * whose scripts write the offers; a site given up on is not.
     */
    private suspend fun read(url: String, words: String, renders: java.util.concurrent.atomic.AtomicInteger? = null): Page {
        val domain = SiteRecipes.domainOf(url)
        val givenUp = SiteGaps.isGivenUp(domain)
        // A re-read in the browser is taken from the search's budget before asking, and given back
        // where the page did not need it, so pages read at once cannot all take the last one.
        val render = !givenUp && (renders == null || renders.getAndDecrement() > 0)
        val page = try {
            json.decodeFromJsonElement(ShopPage.serializer(), io.github.tieo.arbay.neuland.Neuland.request(
                io.ktor.http.HttpMethod.Get, "shops/page?url=${url.encodeUrl()}&render=$render", limitS = PAGE_TIMEOUT_MS / 1000,
            ))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (render) renders?.incrementAndGet()
            log.debug("Web: {} not read: {}", url, e.message?.take(80))
            return Page(Outcome.BLOCKED)
        }
        if (render && !page.readAgain) renders?.incrementAndGet()
        return when (page.outcome) {
            "offers" -> Page(Outcome.OFFERS, page.offers)
            "nothing-sold" -> Page(Outcome.NOTHING_SOLD)
            "blocked" -> Page(Outcome.BLOCKED)
            else -> {
                if (givenUp) return Page(Outcome.NOTHING_SOLD)
                // Reported either way, so the site gets its exact recipe; the guess only keeps this
                // search from missing the offer until then.
                page.html?.let { SiteGaps.record(domain, url, words, it, recipeBroken = page.recipeBroken) }
                coroutineContext[UnreadableSites]?.sites?.add(domain)
                if (page.outcome == "guessed" && page.offers.isNotEmpty()) Page(Outcome.GUESSED, page.offers) else Page(Outcome.UNREADABLE)
            }
        }
    }

    @kotlinx.serialization.Serializable
    private data class ShopPage(
        val outcome: String, val offers: List<ShopOffer> = emptyList(), val recipeBroken: Boolean = false,
        val readAgain: Boolean = false, val html: String? = null,
    )

    @kotlinx.serialization.Serializable
    internal data class Amount(val cents: Long, val currency: String) {
        /** The amount in its own currency; none for a currency Arbay does not know, which is never
         *  taken for euros. */
        fun money(): io.github.tieo.arbay.model.Money? = io.github.tieo.arbay.model.Currency.entries.firstOrNull { it.name == currency }?.let { io.github.tieo.arbay.model.Money(cents, it) }
    }

    @kotlinx.serialization.Serializable
    internal data class Rate(val period: String, val price: Amount)

    @kotlinx.serialization.Serializable
    internal data class Offered(val price: Amount, val code: String? = null, val terms: String? = null)

    /** One offer as Neuland read it off a shop's page. */
    @kotlinx.serialization.Serializable
    internal data class ShopOffer(
        val title: String, val price: Amount, val url: String, val condition: String? = null,
        @kotlinx.serialization.SerialName("in_stock") val inStock: Boolean = true,
        val image: String? = null, val shipping: Amount? = null, val delivery: io.github.tieo.arbay.model.Delivery? = null,
        val gtin: String? = null, val mpn: String? = null,
        val seller: String? = null,
        @kotlinx.serialization.SerialName("rent_per") val rentPer: String? = null,
        @kotlinx.serialization.SerialName("rent_rates") val rentRates: List<Rate> = emptyList(),
        val coupon: Offered? = null,
    )

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Whether the page belongs to a market Arbay crawls by its own crawler, which reads it better. */
    private fun coveredElsewhere(url: String): Boolean {
        val host = SiteRecipes.domainOf(url)
        return host.isEmpty() || siteName(host) in COVERED ||
            SKIPPED.any { host == it || host.endsWith(".$it") } || url.substringBefore('?').lowercase().endsWith(".pdf")
    }

    private fun idOf(url: String, title: String): String =
        MessageDigest.getInstance("SHA-1").digest("$url|$title".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)


    companion object {

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
         *  offer a new one for every engine that listed it. AliExpress's search writes a new
         *  set into each item's link on every page it serves (`algo_pvid`, `pdp_npi`…), and one
         *  RAM board stood twice in one search. */
        private val TRACKING_PARAM = Regex("""(?<=[?&])(utm_[a-z]+|gclid|gbraid|wbraid|fbclid|msclkid|gad_source|gad_campaignid|srsltid|lnid|campaign_type|msockid|_x_tr_[a-z_]+|zenid|oscsid|phpsessid|algo_[a-z_]+|pdp_[a-z_]+|aem_p4p_[a-z_]+|search_p4p_id|curPageLogUid|utparam-url|spm|scm)=[^&#]*&?""", RegexOption.IGNORE_CASE)

        private val SESSION_IN_PATH = Regex(""";(jsessionid|sid|phpsessid)=[^?#/]*""", RegexOption.IGNORE_CASE)



        /** Pages read per search of what the engines list, beside each learned site's own search. */
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
        /**
         * One shop's offer once, however many of its country sites list it: compuram sells its RN12H
         * board on compuram.de, compuram.de/it and compuram.biz/fr, the same title and price at three
         * addresses, and it stood three times in one search. Offers of one site keep apart, as a
         * shop's variants of one title and price do.
         */
        internal fun oneShopOnce(listings: List<Listing>): List<Listing> = ShopOnce().keep(listings)


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

/** One search's record of the shop offers already taken, by shop name, title and price, with the
 *  site each came from: the web market streams page by page, and compuram's French site, read
 *  after its German one, showed the same board again. */
internal class ShopOnce {
    private val seen = HashMap<Triple<String, String, Long>, MutableSet<String>>()

    @Synchronized
    fun keep(listings: List<Listing>): List<Listing> = listings.filter { l ->
        val domain = SiteRecipes.domainOf(l.url)
        val path = runCatching { java.net.URI(l.url).rawPath.orEmpty() }.getOrDefault("")
        // A country site under one host ("compuram.de/it") is a site of its own.
        val origin = "$domain${path.split('/').getOrNull(1)?.takeIf { it.length == 2 }?.let { "/$it" }.orEmpty()}"
        val origins = seen.getOrPut(Triple(WebCrawler.siteName(domain), l.title.lowercase().trim(), l.price.amount)) { mutableSetOf() }
        (origins.isEmpty() || origin in origins).also { if (it) origins += origin }
    }
}
