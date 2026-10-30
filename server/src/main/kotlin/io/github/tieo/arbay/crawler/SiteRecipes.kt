package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.RentPeriod
import io.github.tieo.arbay.model.RentRate
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory

/**
 * How one site's pages are read where they carry no structured data [ProductPages] reads.
 *
 * Data, not code: CSS selectors, each optionally followed by `@attribute` to read that attribute
 * instead of the element's text (`span.price@content`, `a.title@href`). Written by the agent the
 * [SiteGaps] feed reaches, checked against the real page through `POST /api/web/try` before it is
 * stored, and used from the next search on with no deploy. A recipe that stops finding offers on
 * a page that still sells something is reported as a gap again, so a site that changes its pages
 * gets its recipe rewritten.
 */
@Serializable
data class SiteRecipe(
    /** The site, as its host without "www.": "example.de". */
    val domain: String,
    /** Which of the site's pages this reads, as a regex on the address; every page where null. */
    val urlPattern: String? = null,
    /** Each offer's box on a page listing several; null where the page is one offer. */
    val item: String? = null,
    val title: String = "",
    val price: String = "",
    /**
     * For offers a page holds only in a script, not in its elements: a regex over the page's
     * source with the named groups `title` and `price`, and `link` and `image` where it has them.
     * Pearl's lists split each product across rows no box holds, and state it whole only as
     * `new Product('ZX4189','Verbatim …',12.99,'/a-ZX4189-1162.shtml')`. Replaces [item],
     * [title], [price], [link] and [image] where set.
     */
    val pattern: String? = null,
    /** A page-level element naming what the offers are, where each names only its model:
     *  abschliff.de's table lists "ELF", "Trio" under "Parkettschleifmaschinen Verleih", and the
     *  title reads "ELF · Parkettschleifmaschinen Verleih". */
    val context: String? = null,
    /** The offer's maker, where the box names it apart from the title: bauhaus.info writes
     *  "Geberit" on a line above "Wand-Dusch-WC-Set AquaClean Alba". Put before a title that
     *  does not name it already. */
    val brand: String? = null,
    /** The box's line offering a code for a lower price ("Mit dem Code DYSON: Jetzt für nur
     *  779 €"): the code and the price it gives are read off it. */
    val coupon: String? = null,
    /**
     * For a site whose offers come from its own JSON API (searched through [searchUrl]): the path
     * to the list of offers in the answer, "products". [title], [price], [link], [image] and
     * [rates] are then paths inside each offer, dot-separated, and a segment may pick one entry of
     * a list by its fields: Boels' day rate is `unitPrices[unit=DAY-RENT,netPrice=false].value`,
     * since the list's own `price` is none of its rates.
     */
    val items: String? = null,
    /** What a link starting with "/" is read under, where that is not the page's own address:
     *  an API at /api/occ/... links its products as "/mieten/<name>/p/<code>". */
    val linkBase: String? = null,
    /** The currency's code, or a selector for where the page states it; euros where null. */
    val currency: String? = null,
    /** The offer's own page, inside its box. */
    val link: String? = null,
    val image: String? = null,
    val condition: String? = null,
    /** An element present only on an offer that cannot be bought now. */
    val soldOut: String? = null,
    /** For a site that hires things out rather than selling them, the stretch of time [price]
     *  reads the cost of: its day rate wherever it states one, so rentals compare. */
    val rentPer: RentPeriod? = null,
    /** Whether the site states its prices without VAT, as a rental firm for trades does
     *  (maschinen-humpert.de: "*Preisangabe netto zzgl. MwSt."): Germany's 19 % is added, so the
     *  price is what a person pays. */
    val pricesNet: Boolean = false,
    /** Where a site shows net prices only on some pages, what marks them: Conrad shows a client
     *  it does not know its business prices, its header then reading "Geschäftskunde". */
    val netWhere: String? = null,
    /** A rental's other rates, each a selector inside the offer's box, by the time they pay for. */
    val rates: Map<RentPeriod, String> = emptyMap(),
    /** Whether the offers only appear once a browser has run the page's scripts. */
    val render: Boolean = false,
    /** The site's own search for some words, with `{q}` where the words go: from then on the site
     *  is searched directly, not only when a search engine happens to list one of its pages. */
    val searchUrl: String? = null,
    /** An element the site shows only where its own search found nothing ("Es wurden keine
     *  Artikel gefunden"): such a page sells nothing for the words, rather than being a page the
     *  recipe cannot read, which is reported. */
    val nothingFound: String? = null,
    /** Why the recipe reads the page this way, for whoever rewrites it. */
    val note: String? = null,
    val updatedAt: Instant? = null,
)

object SiteRecipes {
    private val log = LoggerFactory.getLogger(SiteRecipes::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val file = DataDir.file("web_recipes.json")

    private var recipes: Map<String, List<SiteRecipe>> =
        (file.readStore(log) { json.decodeFromString<List<SiteRecipe>>(it) } ?: emptyList()).groupBy { it.domain }

    fun all(): List<SiteRecipe> = synchronized(this) { recipes.values.flatten() }

    /** The recipe for this page, the one naming its address before the site-wide one. */
    fun forUrl(url: String): SiteRecipe? {
        val candidates = synchronized(this) { recipes[domainOf(url)] } ?: return null
        return candidates.firstOrNull { r -> r.urlPattern != null && runCatching { Regex(r.urlPattern).containsMatchIn(url) }.getOrDefault(false) }
            ?: candidates.firstOrNull { it.urlPattern == null }
    }

    /** Whether a recipe for this page finds the site's own "nothing found" answer on it. */
    fun foundNothing(url: String, html: String): Boolean {
        val candidates = synchronized(this) { recipes[domainOf(url)] } ?: return false
        val marks = candidates.filter { r -> r.urlPattern == null || runCatching { Regex(r.urlPattern).containsMatchIn(url) }.getOrDefault(false) }
            .mapNotNull { it.nothingFound }
        if (marks.isEmpty()) return false
        val doc = Jsoup.parse(html)
        return marks.any { runCatching { doc.selectFirst(it) != null }.getOrDefault(false) }
    }

    /**
     * The offers the site's recipes read off a page: those naming its address first, the
     * site-wide one where they read nothing. werkzeugprofis.de addresses a machine and a category
     * alike (`/parkettschleifmaschine-klein.html`, `/parkett-dielen.html`); the machine recipe
     * reads the one, the list recipe the other.
     */
    fun readAt(url: String, html: String): List<PageOffer> {
        val candidates = synchronized(this) { recipes[domainOf(url)] } ?: return emptyList()
        val matching = candidates.filter { r -> r.urlPattern != null && runCatching { Regex(r.urlPattern).containsMatchIn(url) }.getOrDefault(false) }
        return (matching + candidates.filter { it.urlPattern == null }).firstNotNullOfOrNull { r -> read(r, html, url).takeIf { it.isNotEmpty() } }.orEmpty()
    }

    /** Keeps [recipe], replacing the site's recipe for the same pages. */
    fun put(recipe: SiteRecipe) {
        val stamped = recipe.copy(domain = recipe.domain.removePrefix("www.").lowercase(), updatedAt = Clock.System.now())
        synchronized(this) {
            val others = recipes[stamped.domain].orEmpty().filter { it.urlPattern != stamped.urlPattern }
            recipes = recipes + (stamped.domain to (others + stamped))
            persist()
        }
    }

    fun remove(domain: String) = synchronized(this) {
        recipes = recipes - domain.removePrefix("www.").lowercase()
        persist()
    }

    private fun persist() {
        runCatching { file.writeTextAtomically(json.encodeToString(recipes.values.flatten())) }
            .onFailure { log.error("site recipes not written: {}", it.message) }
    }

    /**
     * [offers] as the site gives them: a site whose recipe says it hires things out hires out
     * everything read on it, its structured data included. Mietluke states each machine's day
     * rate as a schema.org Offer price, which read alone was a purchase at 45 €.
     */
    fun asTheSiteDeals(url: String, html: String, offers: List<PageOffer>): List<PageOffer> {
        val site = synchronized(this) { recipes[domainOf(url)] } ?: return offers
        val rentPer = site.firstNotNullOfOrNull { it.rentPer }
        // A site showing net prices: what its structured data left unsaid gets the VAT a person pays.
        val net = site.firstOrNull { it.pricesNet }?.let { r -> r.netWhere == null || Jsoup.parse(html).selectFirst(r.netWhere) != null } == true
        return offers.map { o ->
            o.copy(
                rentPer = o.rentPer ?: rentPer,
                price = if (net && !o.vatStated) ProductPages.withVat(o.price, url) else o.price,
                vatStated = o.vatStated || net,
            )
        }
    }

    /**
     * Offers a page's structured data states for other pages, without those a recipe of the site
     * reads at their own page: rental-portal.com's lists give each machine its cheapest tier net
     * (20,00 €), its machine page one day's rent (35,70 €).
     */
    fun withoutOffersReadElsewhere(pageUrl: String, offers: List<PageOffer>): List<PageOffer> {
        val own = synchronized(this) { recipes[domainOf(pageUrl)] }?.mapNotNull { r -> r.urlPattern?.let { runCatching { Regex(it) }.getOrNull() } }
            ?.takeIf { it.isNotEmpty() } ?: return offers
        return offers.filterNot { o -> o.url != pageUrl && own.any { it.containsMatchIn(o.url) && !it.containsMatchIn(pageUrl) } }
    }

    /** The offers [recipe] reads off a page. */
    fun read(recipe: SiteRecipe, html: String, pageUrl: String): List<PageOffer> {
        if (recipe.pattern != null) return readPattern(recipe, recipe.pattern, html, pageUrl)
        if (recipe.items != null) return readJson(recipe, recipe.items, html, pageUrl)
        if (recipe.title.isBlank() || recipe.price.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, pageUrl)
        // A page that is one product is read whole, its head included: its picture is often only
        // in og:image there.
        val boxes: List<Element> = if (recipe.item == null) listOf(doc) else doc.select(recipe.item)
        val pageCurrency = recipe.currency?.let { c -> ProductPages.currencyOf(c) ?: pick(doc, c)?.let(ProductPages::currencyOf) }
        val context = recipe.context?.let { pick(doc, it, spaced = true) }
        return boxes.mapNotNull { box ->
            val maker = recipe.brand?.let { pick(box, it, spaced = true)?.trim() }?.takeIf { it.isNotEmpty() }
            val title = pick(box, recipe.title, spaced = true)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { t -> if (maker != null && !t.contains(maker, ignoreCase = true)) "$maker $t" else t }
                ?.let { t -> if (context != null && !t.contains(context, ignoreCase = true)) "$t · $context" else t } ?: return@mapNotNull null
            val priceText = amountAt(box, recipe.price) ?: return@mapNotNull null
            val currency = pageCurrency ?: ProductPages.currencyOf(priceText.filter { !it.isDigit() && it !in ".,  " }) ?: Currency.EUR
            val price = ProductPages.amount(priceText, currency)?.takeIf { it.amount > 0 }?.let { withVat(recipe, it, doc, pageUrl) } ?: return@mapNotNull null
            PageOffer(
                title = title,
                price = price,
                url = recipe.link?.let { pick(box, it) }?.let { ProductPages.absolute(decodedLink(it), pageUrl) } ?: pageUrl,
                condition = recipe.condition?.let { pick(box, it) }?.let(ProductPages::conditionOf),
                inStock = recipe.soldOut?.let { box.selectFirst(it) == null } ?: true,
                image = recipe.image?.let { pick(box, it) }?.let { ProductPages.absolute(it, pageUrl) },
                rentPer = recipe.rentPer,
                vatStated = true,
                coupon = recipe.coupon?.let { spec -> couponIn(box, spec, currency, price) },
                rentRates = recipe.rates.mapNotNull { (period, spec) ->
                    amountAt(box, spec)?.let { ProductPages.amount(it, currency) }?.takeIf { it.amount > 0 }?.let { RentRate(period, withVat(recipe, it, doc, pageUrl)) }
                },
            )
        }.distinctBy { it.url to it.title }
    }

    private fun readPattern(recipe: SiteRecipe, pattern: String, html: String, pageUrl: String): List<PageOffer> {
        val currency = recipe.currency?.let(ProductPages::currencyOf) ?: Currency.EUR
        fun group(m: MatchResult, name: String) = runCatching { m.groups[name]?.value }.getOrNull()?.let(::unescaped)?.takeIf { it.isNotBlank() }
        return Regex(pattern).findAll(html).mapNotNull { m ->
            val title = group(m, "title") ?: return@mapNotNull null
            val price = group(m, "price")?.let { ProductPages.amount(it, currency) }?.takeIf { it.amount > 0 } ?: return@mapNotNull null
            PageOffer(
                title = title,
                price = price,
                url = group(m, "link")?.let { ProductPages.absolute(it, pageUrl) } ?: pageUrl,
                image = group(m, "image")?.let { ProductPages.absolute(it, pageUrl) },
                rentPer = recipe.rentPer,
            )
        }.toList().distinctBy { it.url to it.title }
    }

    private fun readJson(recipe: SiteRecipe, items: String, body: String, pageUrl: String): List<PageOffer> {
        val root = runCatching { Json.parseToJsonElement(body.trim()) }.getOrNull() ?: return emptyList()
        val list = when (val found = at(root, items)) {
            is JsonArray -> found.toList()
            null -> emptyList()
            else -> listOf(found)
        }
        val currency = recipe.currency?.let(ProductPages::currencyOf) ?: Currency.EUR
        fun text(e: JsonElement, path: String?) = path?.let { at(e, it) as? JsonPrimitive }?.contentOrNull?.takeIf { it.isNotBlank() }
        fun amount(e: JsonElement, path: String) = text(e, path)?.let { ProductPages.amount(it, currency) }?.takeIf { it.amount > 0 }
        return list.mapNotNull { item ->
            // WordPress titles a page's variant "– Parkettschleifmaschine"; the dash is no part of its name.
            val title = text(item, recipe.title)?.let { Jsoup.parse(it).text().trim().trimStart('–', '—', '-', '|', ' ') }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val price = amount(item, recipe.price) ?: return@mapNotNull null
            val link = text(item, recipe.link)?.let { l -> if (l.startsWith("/") && recipe.linkBase != null) recipe.linkBase.trimEnd('/') + l else ProductPages.absolute(l, pageUrl) }
            PageOffer(
                title = title,
                price = price,
                url = link ?: pageUrl,
                image = text(item, recipe.image)?.let { ProductPages.absolute(it, recipe.linkBase ?: pageUrl) },
                rentPer = recipe.rentPer,
                rentRates = recipe.rates.mapNotNull { (period, path) -> amount(item, path)?.let { RentRate(period, it) } },
            )
        }.distinctBy { it.url to it.title }
    }

    /**
     * What a path names inside [root]: `a.b[k=v,k2=v2].c`. A filter's key may itself be a path
     * and its value alternatives (`[acf.mietpreis_einheit=Arbeitstag|Kalendertage]`). A filtered
     * list at the path's end gives every entry that matches, one inside it the first; a list
     * without a filter inside the path gives its first entry.
     */
    private fun at(root: JsonElement, path: String): JsonElement? {
        // A path filtering the answer itself, where the answer is a list: "[k=v]".
        if (path.startsWith("[")) return at(JsonObject(mapOf(ROOT to root)), ROOT + path)
        val segments = Regex("""([^.\[]+)(?:\[([^\]]*)])?""").findAll(path).toList()
        return segments.foldIndexed(root as JsonElement?) { i, e, seg ->
            val next = (e as? JsonObject)?.get(seg.groupValues[1]) ?: return null
            val filter = seg.groupValues[2].split(',').filter { '=' in it }
                .map { it.substringBefore('=').trim() to it.substringAfter('=').split('|').map(String::trim).toSet() }
            val last = i == segments.lastIndex
            fun matches(entry: JsonElement) = filter.all { (k, vs) -> (at(entry, k) as? JsonPrimitive)?.contentOrNull in vs }
            when {
                next is JsonArray && filter.isNotEmpty() && last -> JsonArray(next.filter(::matches))
                next is JsonArray && filter.isNotEmpty() -> next.firstOrNull(::matches)
                next is JsonArray && !last -> next.firstOrNull()
                else -> next
            }
        }
    }

    private const val ROOT = "\u0000"

    /** A string as a script wrote it: its backslash escapes and HTML entities read. */
    private fun unescaped(raw: String): String =
        Jsoup.parse(Regex("""\\(.)""").replace(raw) { it.groupValues[1] }).text().trim()

    /**
     * The amount a price selector names: the one written with its currency where the text holds
     * other numbers too (toom's rate reads "4 Stunden: 44,80 €"), or the n-th amount in the text
     * where the selector ends in `#n` (abschliff's cell "20,- / 30,-" is the day and the weekend
     * rate). A German "20,-" is twenty.
     */
    private fun amountAt(box: Element, spec: String): String? {
        val nth = Regex("""#(\d+)$""").find(spec)
        val text = pick(box, if (nth != null) spec.substring(0, nth.range.first) else spec)?.replace(Regex(""",-+"""), ",00") ?: return null
        if (nth != null) return AMOUNT.findAll(text).elementAtOrNull(nth.groupValues[1].toInt() - 1)?.value
        return WITH_CURRENCY.find(text)?.value ?: text
    }

    /** The code a line names and the price it gives, where that is below the [listed] one. */
    private fun couponIn(box: Element, spec: String, currency: Currency, listed: io.github.tieo.arbay.model.Money): io.github.tieo.arbay.model.Coupon? {
        val text = pick(box, spec, spaced = true) ?: return null
        val price = WITH_CURRENCY.find(text)?.value?.let { ProductPages.amount(it, currency) }?.takeIf { it.amount in 1 until listed.amount } ?: return null
        val code = COUPON_CODE.find(text)?.groupValues?.get(1)
        return io.github.tieo.arbay.model.Coupon(price = price, code = code, terms = text)
    }

    private val COUPON_CODE = Regex("""(?i:code)\W{0,3}([A-Z0-9][A-Z0-9_-]{2,})""")

    private val WITH_CURRENCY = Regex("""\d[\d.,]*\s?(€|\beur\b|\bchf\b|£|\$)|(€|\beur|\bchf|£|\$)\s?\d[\d.,]*""", RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("""\d[\d.]*(,\d{1,2})?""")
    private val CSS_URL = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")

    private fun withVat(recipe: SiteRecipe, price: io.github.tieo.arbay.model.Money, doc: Element, pageUrl: String) =
        if (recipe.pricesNet && (recipe.netWhere == null || doc.selectFirst(recipe.netWhere) != null)) ProductPages.withVat(price, pageUrl) else price

    /** A link as a page hides it: mietmeile.de writes each ad's address base64-encoded into a
     *  `data-submit` its script follows. A value that decodes to an address is that address. */
    private fun decodedLink(raw: String): String {
        if ('/' in raw || '.' in raw || !Regex("""[A-Za-z0-9+_=-]{8,}""").matches(raw)) return raw
        val decoded = runCatching { String(java.util.Base64.getMimeDecoder().decode(raw.replace('-', '+').replace('_', '/'))) }.getOrNull() ?: return raw
        Regex("""location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""").find(raw)?.let { return it.groupValues[1] }
        return decoded.takeIf { it.startsWith("/") || it.startsWith("http") }?.takeIf { d -> d.all { it.code in 33..126 } } ?: raw
    }

    /** What a `selector@attribute` names inside [scope]: the attribute, or the element's text.
     *  [spaced] text keeps a space where one element ends and the next begins, as a name reads on
     *  screen: loxam's `<h1>Parkettschleifmaschine<span>45 kg</span></h1>` is two words apart. A
     *  price is read unspaced, since one written as `12<sup>,99</sup>` is one number. */
    private fun pick(scope: Element, spec: String, spaced: Boolean = false): String? {
        val at = spec.lastIndexOf('@')
        val (selector, attribute) = if (at >= 0 && !spec.substring(at + 1).contains(' ')) spec.substring(0, at) to spec.substring(at + 1) else spec to null
        val el = if (selector.isBlank()) scope else scope.selectFirst(selector) ?: return null
        // A picture set as a CSS background (thalhofer's `style="background-image: url('…')"`)
        // is read as the address inside url().
        if (attribute != null) return el.attr(attribute).let { CSS_URL.find(it)?.groupValues?.get(1) ?: it }.takeIf { it.isNotBlank() }
        val text = if (spaced) buildString { org.jsoup.select.NodeTraversor.traverse({ node, _ -> if (node is org.jsoup.nodes.TextNode) append(node.text()).append(' ') }, el) }.replace(Regex("""\s+"""), " ").trim() else el.text()
        return text.takeIf { it.isNotBlank() }
    }

    fun domainOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.").lowercase()
}
