package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Money
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** One offer as a shop's own page states it. */
@kotlinx.serialization.Serializable
data class PageOffer(
    val title: String,
    val price: Money,
    /** The offer's own page; the page it was read on where that is the offer. */
    val url: String,
    val condition: Condition? = null,
    val inStock: Boolean = true,
    val image: String? = null,
    /** What delivery costs, where the page states it with the offer. */
    val shipping: Money? = null,
    val gtin: String? = null,
    val mpn: String? = null,
    /** Who sells it, where the page names someone other than the site. */
    val seller: String? = null,
    /** For something hired out rather than sold, the stretch of time [price] pays for. */
    val rentPer: io.github.tieo.arbay.model.RentPeriod? = null,
    val rentRates: List<io.github.tieo.arbay.model.RentRate> = emptyList(),
    /** Whether what was read settled the price's VAT: structured data stating it, or a recipe,
     *  which knows its site. A site that shows net prices adds VAT to the others only. */
    val vatStated: Boolean = false,
    /** A code the page gives a lower price for, and that price. */
    val coupon: io.github.tieo.arbay.model.Coupon? = null,
)

/**
 * The offers any shop's page carries in the structured form search engines read.
 *
 * Nearly every shop states its products for Google Shopping in one of three forms: JSON-LD
 * (`<script type="application/ld+json">` with a schema.org Product and its Offer), microdata
 * (`itemprop="price"` on the page's own elements) or OpenGraph's product tags. Read in that order,
 * since JSON-LD is the most complete and OpenGraph the least. A page listing several products
 * states them as an ItemList, and every product in it is an offer. Anything these three do not
 * carry is for a site's own [SiteRecipe].
 */
object ProductPages {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun offers(html: String, pageUrl: String): List<PageOffer> {
        val doc = Jsoup.parse(html, pageUrl)
        return jsonLd(doc, pageUrl).ifEmpty { microdata(doc, pageUrl) }.ifEmpty { openGraph(doc, pageUrl) }
            .ifEmpty { appState(doc, pageUrl) }.ifEmpty { repeatedBoxes(doc, pageUrl) }
            .distinctBy { it.url to it.title }
    }

    /**
     * Whether the page sells something, whether or not anything here could read it: a price in
     * a currency next to a way to buy. A page that does and yields no offer is a site Arbay
     * cannot read yet; an article, a forum thread or a manual is not.
     */
    fun sellsSomething(html: String): Boolean {
        val doc = Jsoup.parse(html)
        if (isArticle(doc) || isForum(doc) || soldOut(doc)) return false
        val text = doc.body()?.text()?.lowercase() ?: return false
        // An amount of nothing is no price: an empty basket's "0,00 €" stands on every page of a
        // shop, its articles too.
        // Nor is what delivery costs, a voucher or an order's minimum: "Versandkosten ab 5,95 €"
        // stands in a shop's header, "10 €-Gutschein ab 100 € Einkaufswert" on its newsletter page.
        // An amount named beside another ("€ 1,99 bzw. € 3,98 Mini-Versand") is what the other is.
        return PRICE_IN_TEXT.findAll(text).any { m ->
            // Read on from the amount's last digit: "€ 3" is how "€ 3,98" matches.
            val end = m.range.last + 1 + (AMOUNT_TAIL.find(text, m.range.last + 1)?.takeIf { it.range.first == m.range.last + 1 }?.value?.length ?: 0)
            val after = text.substring(end, minOf(text.length, end + 45))
            val reach = if (ALTERNATIVE_AMOUNT.containsMatchIn(after)) 45 else 15
            m.value.any { it in '1'..'9' } && !NOT_A_PRICE.containsMatchIn(
                text.substring(maxOf(0, m.range.first - 30), m.range.first) + " " + after.take(reach))
        } && BUY_WORDS.containsMatchIn(text)
    }

    /**
     * Whether the page passes itself off as another shop: it names itself (`og:site_name`) as a
     * shop whose name is a letter or two off its own address. medymarkt.live serves MediaMarkt's
     * pages, name and all, to take payments for goods it never sends.
     */
    fun impersonates(html: String, pageUrl: String): Boolean {
        val claimed = Jsoup.parse(html).selectFirst("meta[property=og:site_name]")?.attr("content")
            ?.lowercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.length >= 5 } ?: return false
        val own = WebCrawler.siteName(SiteRecipes.domainOf(pageUrl)).filter { it.isLetterOrDigit() }
        return own != claimed && own !in claimed && claimed !in own && editDistance(own, claimed) <= 2
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * Whether the page states itself an article: a review or a blog post that names prices and
     * links shops ("Zum Angebot bei cyberport") sells nothing itself. Read off the type its
     * structured data gives the page, and only where it names no product beside it.
     */
    private fun isArticle(doc: Document): Boolean {
        val types = doc.select("script[type=application/ld+json]").flatMap { script ->
            Regex(""""@type"\s*:\s*(\[[^\]]*]|"[^"]*")""").findAll(script.data()).flatMap { m -> Regex(""""([^"]+)"""").findAll(m.groupValues[1]).map { it.groupValues[1].substringAfterLast('/') } }
        }.toSet()
        // A product the page offers makes it a shop's page; one it only reviews does not:
        // matthiashaltenhof.de's test of the Sony A7 III states the camera with a review and no offer.
        if (statedOffers(doc).isNotEmpty() || doc.selectFirst("[itemtype*=schema.org/Product] [itemprop=offers]") != null) return false
        // A blog without structured data says what it is in its OpenGraph type: techtest.org's
        // review of three 18 TB drives names their prices and links Amazon.
        return types.any { it in ARTICLE_TYPES } || doc.selectFirst("meta[property=og:type][content=article]") != null
    }

    /**
     * Whether the page is a thread of a forum's software, which says so in its markup where its
     * structured data does not: bauexpertenforum.de's XenForo thread on an urinal lid's hinge
     * quotes "90€" in a reply and names no product of its own.
     */
    private fun isForum(doc: Document): Boolean =
        doc.selectFirst("html#XenForo, html#XF, html[data-app=public][data-template=thread_view], body#phpbb, #vbulletin_html, meta[name=generator][content*=Discourse], meta[name=generator][content*=phpBB], meta[name=generator][content*=vBulletin]") != null

    private val NOT_A_PRICE = Regex("""versand|shipping|lieferung|porto|livraison|spedizione|gutschein|mindest|einkaufswert|bestellwert|bestellung|warenwert|im wert von|gesamtwert|coupon|voucher|käuferschutz|buyer protection|anruf|/\s?min\b|pro minute""")

    private val AMOUNT_TAIL = Regex("""[\d.,]+""")

    private val ALTERNATIVE_AMOUNT = Regex("""^\s?(€|eur)?\s*(bzw\.?|oder)\s*(€|eur)?\s?\d""")

    // A forum thread too: hardwareluxx.de's posts quote Geizhals prices for what they discuss.
    private val ARTICLE_TYPES = setOf("Article", "BlogPosting", "NewsArticle", "Review", "TechArticle", "Blog",
        "DiscussionForumPosting", "SocialMediaPosting", "QAPage", "Question")

    // The currency's word stands alone: French "largeur 268 mm" and "ordinateur 1 x USB" are no prices.
    private val PRICE_IN_TEXT = Regex("""(€|\beur|\bchf|£)\s?\d|\d[\d.,]*\s?(€|\beur\b|\bchf\b)""")
    private val BUY_WORDS = Regex(
        """warenkorb|in den korb|add to (cart|basket)|jetzt kaufen|sofort.?kaufen|kaufen\b|bestellen|""" +
            """buy now|in den einkaufswagen|panier|carrello|cesta|winkelwagen|zur kasse|""" +
            // A rental is had by hiring it, and is read as a rental offer.
            """\bmieten\b|mietanfrage|\bausleihen\b""",
    )

    /**
     * One product page read the way a person reads it, for a site with no structured data and no
     * recipe yet: the page's one heading and the first price it shows in an element made for a
     * price. A guess, so the site is still reported and gets its exact recipe; until then the
     * search that found the page still shows its offer rather than nothing. Null on a page with
     * several headings or no such price, which is a list or not a product at all.
     */
    fun guess(html: String, pageUrl: String): PageOffer? {
        val doc = Jsoup.parse(html, pageUrl)
        val headings = doc.select("h1").map { it.text().trim() }.filter { it.isNotEmpty() }
        if (headings.size != 1) return null
        val priceEl = priceElement(doc.body() ?: return null) ?: return null
        val raw = priceEl.attr("content").ifBlank { priceEl.attr("data-price") }.ifBlank { priceEl.text() }
        val currency = currencyOf(raw.filter { !it.isDigit() && it !in ".,  -" }.trim()) ?: Currency.EUR
        val price = amount(raw, currency)?.takeIf { it.amount > 0 } ?: return null
        // A price labelled as a day's rent: werkzeugprofis.de's "Tagesmietpreis: 36,00 €".
        val label = (priceEl.parent()?.ownText().orEmpty() + " " + priceEl.text().take(80)).lowercase()
        return PageOffer(
            title = headings.single(),
            price = price,
            rentPer = io.github.tieo.arbay.model.RentPeriod.DAY.takeIf { DAY_RENT.containsMatchIn(label) },
            url = doc.selectFirst("link[rel=canonical]")?.absUrl("href")?.takeIf { it.isNotBlank() } ?: pageUrl,
            image = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { absolute(it, pageUrl) },
        )
    }

    private val DAY_RENT = Regex("""tagesmiet|mietpreis.{0,12}tag|pro tag|/\s?tag\b|tagespreis""")

    /**
     * The element in [scope] holding the price to pay: the innermost one marked as a price, so a
     * box's whole price block, delivery time and list price included, is not read as one. Megabad's
     * `div.price` reads "ca. 1 Woche UVP: 3.211,81 € 2.299,00 €", and its first number made a
     * 2,299 € shower toilet cost 1 €. The struck-through price beside the real one, and a unit
     * price, are not it, nor is anything inside an element marked as one of them.
     */
    private fun priceElement(scope: Element): Element? {
        val found = scope.select("[class*=price], [class*=Price], [class*=preis], [class*=Preis], [id*=price], [id*=preis], [data-price]")
            .filter { el ->
                PRICE_IN_TEXT.containsMatchIn(el.text().lowercase()) &&
                    (listOf(el) + el.parents().takeWhile { it !== scope }).none { e -> OLD_PRICE.any { it in (e.className() + " " + e.id()).lowercase() } }
            }
        return found.firstOrNull { el -> found.none { it !== el && it.parents().contains(el) } }
    }

    private val OLD_PRICE = listOf("old", "uvp", "strike", "was-", "regular", "crossed", "streich", "statt", "unit", "grund", "base")

    // ── The page's own app state ──

    /**
     * The products a shop built with a JavaScript framework hands its page as JSON: Next.js in
     * `__NEXT_DATA__`, others in a `<script type="application/json">`. Any object in it carrying a
     * name and a price is an offer, whatever the shop calls the fields.
     */
    private fun appState(doc: Document, pageUrl: String): List<PageOffer> =
        doc.select("script#__NEXT_DATA__, script[type=application/json]").flatMap { script ->
            val root = runCatching { json.parseToJsonElement(script.data().trim()) }.getOrNull() ?: return@flatMap emptyList()
            val found = mutableListOf<PageOffer>()
            fun walk(e: JsonElement, depth: Int) {
                if (depth > 40 || found.size > 200) return
                when (e) {
                    is JsonObject -> {
                        stateOffer(e, pageUrl)?.let { found += it }
                        e.values.forEach { walk(it, depth + 1) }
                    }
                    is JsonArray -> e.forEach { walk(it, depth + 1) }
                    else -> {}
                }
            }
            walk(root, 0)
            found
        }

    private fun stateOffer(o: JsonObject, pageUrl: String): PageOffer? {
        val title = NAME_KEYS.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content }
            ?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.length >= 6 } ?: return null
        val currency = CURRENCY_KEYS.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.let(::currencyOf) }
        val price = PRICE_KEYS.firstNotNullOfOrNull { k ->
            when (val v = o[k]) {
                is JsonPrimitive -> v.contentOrNull
                // A price object: {"value": 169.99, "currency": "EUR"}, {"amount": ...}, {"current": ...}.
                is JsonObject -> listOf("value", "amount", "current", "gross", "price").firstNotNullOfOrNull { (v[it] as? JsonPrimitive)?.contentOrNull }
                else -> null
            }
        }?.let { amount(it, currency ?: Currency.EUR) }?.takeIf { it.amount > 0 } ?: return null
        val link = URL_KEYS.firstNotNullOfOrNull { k -> (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.startsWith("/") || it.startsWith("http") } }
        val image = IMAGE_KEYS.firstNotNullOfOrNull { k -> imageOf(o[k]) }
        return PageOffer(
            title = title,
            price = price,
            url = link?.let { absolute(it, pageUrl) } ?: pageUrl,
            image = image?.let { absolute(it, pageUrl) },
        )
    }

    private val NAME_KEYS = listOf("name", "title", "productName", "product_name", "displayName")
    private val PRICE_KEYS = listOf("price", "salePrice", "currentPrice", "finalPrice", "priceValue", "sellingPrice", "offerPrice", "priceGross")
    private val CURRENCY_KEYS = listOf("currency", "currencyCode", "priceCurrency")
    private val URL_KEYS = listOf("url", "link", "href", "productUrl", "canonicalUrl")
    private val IMAGE_KEYS = listOf("image", "imageUrl", "thumbnail", "img", "picture")

    // ── Repeated boxes ──

    /**
     * The offers of a list page read by its shape: a list shows one box per product, every box
     * built alike, each with a link and a price. The most repeated box that carries both is taken,
     * at least [MIN_BOXES] of them, and each is read for its name, price, link and picture.
     */
    private fun repeatedBoxes(doc: Document, pageUrl: String): List<PageOffer> {
        val body = doc.body() ?: return emptyList()
        var best: List<Element> = emptyList()
        for (parent in body.select("*")) {
            val children = parent.children()
            if (children.size < MIN_BOXES) continue
            val alike = children.groupBy { it.tagName() + "." + it.classNames().sorted().joinToString(".") }.values.maxByOrNull { it.size } ?: continue
            if (alike.size < MIN_BOXES || alike.size <= best.size) continue
            val priced = alike.filter { box -> box.selectFirst("a[href]") != null && PRICE_IN_TEXT.containsMatchIn(box.text().lowercase()) }
            if (priced.size >= MIN_BOXES && priced.size * 10 >= alike.size * 6) best = priced
        }
        return best.mapNotNull { box -> boxOffer(box, pageUrl) }
    }

    private fun boxOffer(box: Element, pageUrl: String): PageOffer? {
        val heading = box.selectFirst("h2, h3, h4, [class*=title], [class*=Title], [class*=name], [class*=Name]")
        val link = heading?.selectFirst("a[href]") ?: heading?.closest("a[href]")
            ?: box.select("a[href]").maxByOrNull { it.text().length }
        val title = (heading?.text()?.takeIf { it.length >= 6 } ?: link?.text())?.trim()
            ?.takeIf { it.length >= 6 && !CALL_TO_ACTION.containsMatchIn(it.lowercase()) } ?: return null
        val priceEl = priceElement(box)
        val priceText = priceEl?.let { it.attr("content").ifBlank { it.attr("data-price") }.ifBlank { it.text() } }
            ?: PRICE_IN_TEXT.find(box.text().lowercase())?.value ?: return null
        val currency = currencyOf(priceText.filter { !it.isDigit() && it !in ".,  -" }.trim()) ?: Currency.EUR
        val price = amount(priceText, currency)?.takeIf { it.amount > 0 } ?: return null
        val img = box.selectFirst("img")
        return PageOffer(
            title = title,
            price = price,
            url = link?.absUrl("href")?.takeIf { it.isNotBlank() } ?: pageUrl,
            image = img?.let { i -> listOf("src", "data-src", "data-original").firstNotNullOfOrNull { a -> i.absUrl(a).takeIf { it.isNotBlank() && !it.startsWith("data:") } } },
        )
    }

    private const val MIN_BOXES = 3

    /** A button's words, which a test report's offer boxes carry where a shop's carry the name:
     *  "Zum Angebot bei cyberport" is a link, not a product. */
    private val CALL_TO_ACTION = Regex("""^(zum angebot|jetzt kaufen|in den warenkorb|angebot ansehen|zum shop|zum produkt|details|mehr erfahren|buy now|view deal|check price)""")

    // ── JSON-LD ──

    /**
     * A page's JSON-LD, all of its blocks read as one: a shop may state the product in one block
     * and its offer in another, the product pointing at it by `@id` (voelkner's
     * `"offers": {"@id": "Y524003#offer"}`), so a reference is looked up across every block.
     */
    private fun jsonLd(doc: Document, pageUrl: String): List<PageOffer> {
        val all = doc.select("script[type=application/ld+json]").mapNotNull { parseLenient(it.data().trim()) }.flatMap { nodes(it) }
        val byId = all.filter { it.size > 1 }.mapNotNull { n -> n.str("@id")?.let { it to n } }.toMap()
        return all.flatMap { productsIn(it) }.mapNotNull { offerOf(it, pageUrl, byId) }
    }

    /**
     * JSON as shops write it into their pages, which is not always JSON: pearl writes a size's
     * inch mark into a name unescaped (`"name": "Gehäuse für 2,5"-SATA-HDD"`), others a raw line
     * break. Where the text does not parse, a quote inside a string that does not end it (no
     * comma, colon or bracket follows) is escaped, and so is a control character, and the text is
     * read again.
     */
    internal fun parseLenient(text: String): JsonElement? =
        runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: runCatching { json.parseToJsonElement(repaired(text)) }.getOrNull()

    private fun repaired(text: String): String = buildString(text.length + 16) {
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' && i + 1 < text.length -> { append(c).append(text[i + 1]); i++ }
                inString && c == '"' -> {
                    var j = i + 1
                    while (j < text.length && text[j].isWhitespace()) j++
                    if (j >= text.length || text[j] in ",:}]") { inString = false; append(c) } else append("\\\"")
                }
                inString && c < ' ' -> append(if (c == '\n') "\\n" else if (c == '\t') "\\t" else " ")
                c == '"' -> { inString = true; append(c) }
                else -> append(c)
            }
            i++
        }
    }

    /** Every object of a JSON-LD block, whether it came as one, an array or a @graph. */
    private fun nodes(e: JsonElement): List<JsonObject> = when (e) {
        is JsonArray -> e.flatMap { nodes(it) }
        is JsonObject -> listOf(e) + ((e["@graph"] as? JsonArray)?.flatMap { nodes(it) } ?: emptyList())
        else -> emptyList()
    }

    /**
     * Whether the page's structured data states offers and every one of them unavailable: the
     * shop sells the thing and has none of it now. bergfreunde.de's Petzl GriGri+ states its one
     * variant at price 0, OutOfStock; nothing there can be read because nothing there is for sale.
     */
    private fun soldOut(doc: Document): Boolean {
        val offers = statedOffers(doc)
        return offers.isNotEmpty() && offers.none { inStock(it) }
    }

    /** Every offer the page's structured data states, however deep in it, reviews aside. */
    private fun statedOffers(doc: Document): List<JsonObject> {
        val offers = mutableListOf<JsonObject>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonArray -> e.forEach(::walk)
                is JsonObject -> {
                    if (e.types().any { it == "Offer" || it == "AggregateOffer" }) offers += e
                    e.forEach { (k, v) -> if (k != "review") walk(v) }
                }
                else -> {}
            }
        }
        doc.select("script[type=application/ld+json]").mapNotNull { parseLenient(it.data().trim()) }.forEach(::walk)
        return offers
    }

    private fun JsonObject.types(): List<String> = when (val t = this["@type"]) {
        is JsonPrimitive -> listOfNotNull(t.contentOrNull)
        is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        else -> emptyList()
    }.map { it.substringAfterLast('/') }

    /** The products a node is or holds: itself, a group's variants, a list's items. */
    private fun productsIn(node: JsonObject): List<JsonObject> {
        val types = node.types()
        return when {
            "ProductGroup" in types -> {
                val variants = (node["hasVariant"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                variants.map { v -> if (v["name"] == null && node["name"] != null) JsonObject(v + ("name" to node["name"]!!)) else v }
                    .ifEmpty { listOf(node) }
            }
            types.any { it in PRODUCT_TYPES } -> listOf(node)
            "ItemList" in types || "OfferCatalog" in types ->
                (node["itemListElement"] as? JsonArray).orEmpty().flatMap { el ->
                    val o = el as? JsonObject ?: return@flatMap emptyList()
                    val item = o["item"] as? JsonObject
                    when {
                        item != null -> productsIn(item)
                        o.types().any { it in PRODUCT_TYPES } -> listOf(o)
                        else -> emptyList()
                    }
                }
            // A page stating the product as what an action or the page is about: MediaMarkt's
            // BuyAction carries it as its `object`, others a WebPage its `mainEntity`.
            else -> listOf("object", "mainEntity").flatMap { k -> (node[k] as? JsonObject)?.let { productsIn(it) }.orEmpty() }
        }
    }

    private val PRODUCT_TYPES = setOf("Product", "IndividualProduct", "Vehicle", "Car", "SomeProducts")

    private fun offerOf(product: JsonObject, pageUrl: String, byId: Map<String, JsonObject> = emptyMap()): PageOffer? {
        // A reference only: `{"@id": "..."}` standing for a node stated elsewhere on the page.
        fun deref(o: JsonObject): JsonObject = if (o.size == 1 && o["@id"] != null) byId[o.str("@id")] ?: o else o
        // Some shops put markup into the name ("Festplattengeh<wbr/>äuse", "2,5&#034;").
        val title = product.str("name")?.let { Jsoup.parse(it).text() }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val offers = when (val o = product["offers"]) {
            is JsonObject -> listOf(deref(o))
            is JsonArray -> o.mapNotNull { (it as? JsonObject)?.let(::deref) }
            else -> emptyList()
        }.flatMap { o ->
            // An AggregateOffer may list its offers; its own lowPrice stands for them otherwise.
            val inner = (o["offers"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            inner.ifEmpty { listOf(o) }
        }
        // The cheapest offer that can be bought; the cheapest of all where none says.
        val priced = offers.mapNotNull { o -> priceOf(o)?.let { o to withVatWhereNet(o, it, pageUrl) } }
        val vatStated = offers.any { o -> (listOf(o) + specifications(o["priceSpecification"])).any { it["valueAddedTaxIncluded"] != null } }
        val (offer, price) = priced.filter { (o, _) -> inStock(o) }.minByOrNull { it.second.amount }
            ?: priced.minByOrNull { it.second.amount } ?: return null
        return PageOffer(
            title = title,
            price = price,
            url = (offer.str("url") ?: product.str("url"))?.let { absolute(it, pageUrl) } ?: pageUrl,
            condition = conditionOf(offer.str("itemCondition") ?: product.str("itemCondition")),
            inStock = inStock(offer),
            image = imageOf(product["image"])?.let { absolute(it, pageUrl) },
            shipping = shippingOf(offer, price.currency),
            gtin = listOf("gtin13", "gtin", "gtin14", "gtin12", "gtin8").firstNotNullOfOrNull { product.str(it) },
            mpn = product.str("mpn") ?: product.str("model"),
            seller = (offer["seller"] as? JsonObject)?.str("name"),
            vatStated = vatStated,
        )
    }

    private fun priceOf(offer: JsonObject): Money? {
        val currency = currencyOf(offer.str("priceCurrency"))
            ?: (offer["priceSpecification"] as? JsonObject)?.str("priceCurrency")?.let(::currencyOf)
            ?: Currency.EUR
        val raw = offer.str("price") ?: offer.str("lowPrice")
            ?: specifications(offer["priceSpecification"]).firstNotNullOfOrNull { it.str("price") }
            ?: return null
        return amount(raw, currency)?.takeIf { it.amount > 0 }
    }

    /**
     * A price as a person pays it, where the shop states it without VAT: Conrad serves its
     * business prices to a client it does not know, `"valueAddedTaxIncluded": false`, the
     * Renkforce 4-Bay at 109,24 € that costs 129,99 €. The VAT of the shop's country is added.
     */
    private fun withVatWhereNet(offer: JsonObject, price: Money, pageUrl: String): Money {
        val net = (listOf(offer) + specifications(offer["priceSpecification"])).any { o ->
            (o["valueAddedTaxIncluded"] as? JsonPrimitive)?.contentOrNull?.lowercase() == "false"
        }
        if (!net) return price
        return withVat(price, pageUrl)
    }

    /** [price] with the standard VAT of the shop's country, read off its address's ending. */
    internal fun withVat(price: Money, pageUrl: String): Money {
        val ending = SiteRecipes.domainOf(pageUrl).substringAfterLast('.').uppercase().let { if (it == "UK") "GB" else it }
        val rate = io.github.tieo.arbay.model.STANDARD_VAT[ending] ?: io.github.tieo.arbay.model.STANDARD_VAT.getValue("DE")
        return price.copy(amount = Math.round(price.amount * (1 + rate)))
    }

    private fun specifications(e: JsonElement?): List<JsonObject> = when (e) {
        is JsonObject -> listOf(e)
        is JsonArray -> e.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }

    private fun inStock(offer: JsonObject): Boolean {
        val a = offer.str("availability")?.substringAfterLast('/')?.lowercase() ?: return true
        return a !in setOf("outofstock", "soldout", "discontinued")
    }

    private fun shippingOf(offer: JsonObject, currency: Currency): Money? {
        val details = specifications(offer["shippingDetails"]).firstOrNull() ?: return null
        val rate = details["shippingRate"] as? JsonObject ?: return null
        val raw = rate.str("value") ?: rate.str("price") ?: return null
        return amount(raw, currencyOf(rate.str("currency")) ?: currency)
    }

    private fun imageOf(e: JsonElement?): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonArray -> e.firstNotNullOfOrNull { imageOf(it) }
        is JsonObject -> e.str("url") ?: e.str("contentUrl")
        else -> null
    }

    private fun JsonObject.str(key: String): String? = when (val v = this[key]) {
        is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonObject -> v.str("@id") ?: v.str("name")
        is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    // ── Microdata ──

    private fun microdata(doc: Document, pageUrl: String): List<PageOffer> =
        // By its type alone: reichelt states every product's type and leaves out `itemscope`.
        doc.select("[itemtype*=schema.org/Product]").mapNotNull { product ->
            val title = prop(product, "name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val currency = currencyOf(prop(product, "priceCurrency")) ?: Currency.EUR
            val price = (prop(product, "price") ?: prop(product, "lowPrice"))?.let { amount(it, currency) }
                ?.takeIf { it.amount > 0 } ?: return@mapNotNull null
            val availability = prop(product, "availability")?.substringAfterLast('/')?.lowercase()
            PageOffer(
                title = title,
                price = price,
                url = prop(product, "url")?.let { absolute(it, pageUrl) } ?: pageUrl,
                condition = conditionOf(prop(product, "itemCondition")),
                inStock = availability !in setOf("outofstock", "soldout", "discontinued"),
                image = prop(product, "image")?.let { absolute(it, pageUrl) },
                gtin = prop(product, "gtin13") ?: prop(product, "gtin"),
                mpn = prop(product, "mpn"),
            )
        }

    /** A microdata property of [scope], from the attribute that carries its value. */
    private fun prop(scope: Element, name: String): String? {
        val el = scope.selectFirst("[itemprop=$name]") ?: return null
        return listOf("content", "href", "src", "value").firstNotNullOfOrNull { a -> el.attr(a).takeIf { it.isNotBlank() } }
            ?: el.text().takeIf { it.isNotBlank() }
    }

    // ── OpenGraph ──

    private fun openGraph(doc: Document, pageUrl: String): List<PageOffer> {
        fun meta(vararg names: String) = names.firstNotNullOfOrNull { n ->
            doc.selectFirst("meta[property=$n], meta[name=$n]")?.attr("content")?.takeIf { it.isNotBlank() }
        }
        val raw = meta("product:price:amount", "og:price:amount", "product:sale_price:amount") ?: return emptyList()
        val currency = currencyOf(meta("product:price:currency", "og:price:currency")) ?: Currency.EUR
        val price = amount(raw, currency)?.takeIf { it.amount > 0 } ?: return emptyList()
        val title = meta("og:title")?.trim() ?: doc.title().trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val availability = meta("product:availability", "og:availability")?.lowercase()
        return listOf(PageOffer(
            title = title,
            price = price,
            url = meta("og:url")?.let { absolute(it, pageUrl) } ?: pageUrl,
            condition = conditionOf(meta("product:condition", "og:condition")),
            inStock = availability == null || !availability.contains("out of stock") && !availability.contains("oos"),
            image = meta("og:image")?.let { absolute(it, pageUrl) },
        ))
    }

    // ── Shared ──

    /** An amount as a page states it: "169.99" in structured data, "169,99 €" in text. */
    internal fun amount(raw: String, currency: Currency): Money? {
        val plain = raw.trim()
        if (Regex("""^\d+(\.\d+)?$""").matches(plain))
            return Money(BigDecimal(plain).movePointRight(2).toLong(), currency)
        return Money.parse(plain, currency)
    }

    internal fun currencyOf(code: String?): Currency? {
        val c = code?.trim()?.uppercase() ?: return null
        return when (c) {
            "€" -> Currency.EUR
            "$" -> Currency.USD
            "£" -> Currency.GBP
            else -> runCatching { Currency.valueOf(c) }.getOrNull()
        }
    }

    /** schema.org's condition, as a URL or a bare name, or a page's own word for it. */
    internal fun conditionOf(raw: String?): Condition? {
        val v = raw?.substringAfterLast('/')?.lowercase()?.trim() ?: return null
        return when {
            v.startsWith("newcondition") -> Condition.NEW
            v.startsWith("usedcondition") -> Condition.USED
            v.startsWith("refurbishedcondition") -> Condition.REFURBISHED
            v.startsWith("damagedcondition") -> Condition.PARTS_ONLY
            else -> Condition.parse(raw)
        }
    }

    /**
     * The model a shop names only in the offer's address: MediaMarkt titles the HIYORI AOI488
     * "HIYORI Externes 3,5"-Festplattengehäuse USB 3.0 4 Einschübe" and writes the model into
     * the slug alone (`/_hiyori-aoi488-festplattengehause-148285129.html`). A word of the last
     * path segment carrying letters and digits both, which the title leaves out; the shop's own
     * article number (digits only) is not one.
     */
    fun modelInAddress(url: String, title: String): String? {
        val slug = runCatching { java.net.URI(url).path }.getOrNull()?.substringAfterLast('/')?.substringBeforeLast('.') ?: return null
        val inTitle = title.lowercase().replace(Regex("""[^\p{L}\p{N}]"""), "")
        return slug.lowercase().split('-', '_').firstOrNull { w ->
            w.length in 4..20 && w.any { it.isDigit() } && w.any { it.isLetter() } && w.all { it.isLetterOrDigit() } && w !in inTitle
        }
    }

    internal fun absolute(href: String, pageUrl: String): String =
        runCatching { java.net.URI(pageUrl).resolve(href.trim()).toString() }.getOrDefault(href)
}
