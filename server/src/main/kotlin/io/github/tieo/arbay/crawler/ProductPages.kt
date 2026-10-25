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
            .distinctBy { it.url to it.title }
    }

    /**
     * Whether the page sells something, whether or not anything here could read it: a price in
     * a currency next to a way to buy. A page that does and yields no offer is a site Arbay
     * cannot read yet; an article, a forum thread or a manual is not.
     */
    fun sellsSomething(html: String): Boolean {
        val text = Jsoup.parse(html).body()?.text()?.lowercase() ?: return false
        return PRICE_IN_TEXT.containsMatchIn(text) && BUY_WORDS.containsMatchIn(text)
    }

    private val PRICE_IN_TEXT = Regex("""(€|eur|chf|£)\s?\d|\d[\d.,]*\s?(€|eur\b|chf\b)""")
    private val BUY_WORDS = Regex(
        """warenkorb|in den korb|add to (cart|basket)|jetzt kaufen|sofort.?kaufen|kaufen\b|bestellen|""" +
            """buy now|in den einkaufswagen|panier|carrello|cesta|winkelwagen|zur kasse""",
    )

    // ── JSON-LD ──

    private fun jsonLd(doc: Document, pageUrl: String): List<PageOffer> =
        doc.select("script[type=application/ld+json]").flatMap { script ->
            val root = runCatching { json.parseToJsonElement(script.data().trim()) }.getOrNull() ?: return@flatMap emptyList()
            nodes(root).flatMap { productsIn(it) }.mapNotNull { offerOf(it, pageUrl) }
        }

    /** Every object of a JSON-LD block, whether it came as one, an array or a @graph. */
    private fun nodes(e: JsonElement): List<JsonObject> = when (e) {
        is JsonArray -> e.flatMap { nodes(it) }
        is JsonObject -> listOf(e) + ((e["@graph"] as? JsonArray)?.flatMap { nodes(it) } ?: emptyList())
        else -> emptyList()
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
            else -> emptyList()
        }
    }

    private val PRODUCT_TYPES = setOf("Product", "IndividualProduct", "Vehicle", "Car", "SomeProducts")

    private fun offerOf(product: JsonObject, pageUrl: String): PageOffer? {
        val title = product.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val offers = when (val o = product["offers"]) {
            is JsonObject -> listOf(o)
            is JsonArray -> o.mapNotNull { it as? JsonObject }
            else -> emptyList()
        }.flatMap { o ->
            // An AggregateOffer may list its offers; its own lowPrice stands for them otherwise.
            val inner = (o["offers"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            inner.ifEmpty { listOf(o) }
        }
        // The cheapest offer that can be bought; the cheapest of all where none says.
        val priced = offers.mapNotNull { o -> priceOf(o)?.let { o to it } }
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
            mpn = product.str("mpn"),
            seller = (offer["seller"] as? JsonObject)?.str("name"),
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
        doc.select("[itemscope][itemtype*=schema.org/Product]").mapNotNull { product ->
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

    internal fun absolute(href: String, pageUrl: String): String =
        runCatching { java.net.URI(pageUrl).resolve(href.trim()).toString() }.getOrDefault(href)
}
