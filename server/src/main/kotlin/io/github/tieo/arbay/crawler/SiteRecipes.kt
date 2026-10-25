package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.DataDir
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.repo.readStore
import io.github.tieo.arbay.repo.writeTextAtomically
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    val title: String,
    val price: String,
    /** The currency's code, or a selector for where the page states it; euros where null. */
    val currency: String? = null,
    /** The offer's own page, inside its box. */
    val link: String? = null,
    val image: String? = null,
    val condition: String? = null,
    /** An element present only on an offer that cannot be bought now. */
    val soldOut: String? = null,
    /** Whether the offers only appear once a browser has run the page's scripts. */
    val render: Boolean = false,
    /** The site's own search for some words, with `{q}` where the words go: from then on the site
     *  is searched directly, not only when a search engine happens to list one of its pages. */
    val searchUrl: String? = null,
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

    /** The offers [recipe] reads off a page. */
    fun read(recipe: SiteRecipe, html: String, pageUrl: String): List<PageOffer> {
        val doc = Jsoup.parse(html, pageUrl)
        val boxes: List<Element> = if (recipe.item == null) listOf(doc.body() ?: return emptyList()) else doc.select(recipe.item)
        val pageCurrency = recipe.currency?.let { c -> ProductPages.currencyOf(c) ?: pick(doc, c)?.let(ProductPages::currencyOf) }
        return boxes.mapNotNull { box ->
            val title = pick(box, recipe.title)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val priceText = pick(box, recipe.price) ?: return@mapNotNull null
            val currency = pageCurrency ?: ProductPages.currencyOf(priceText.filter { !it.isDigit() && it !in ".,  " }) ?: Currency.EUR
            val price = ProductPages.amount(priceText, currency)?.takeIf { it.amount > 0 } ?: return@mapNotNull null
            PageOffer(
                title = title,
                price = price,
                url = recipe.link?.let { pick(box, it) }?.let { ProductPages.absolute(it, pageUrl) } ?: pageUrl,
                condition = recipe.condition?.let { pick(box, it) }?.let(ProductPages::conditionOf),
                inStock = recipe.soldOut?.let { box.selectFirst(it) == null } ?: true,
                image = recipe.image?.let { pick(box, it) }?.let { ProductPages.absolute(it, pageUrl) },
            )
        }.distinctBy { it.url to it.title }
    }

    /** What a `selector@attribute` names inside [scope]: the attribute, or the element's text. */
    private fun pick(scope: Element, spec: String): String? {
        val at = spec.lastIndexOf('@')
        val (selector, attribute) = if (at > 0 && !spec.substring(at + 1).contains(' ')) spec.substring(0, at) to spec.substring(at + 1) else spec to null
        val el = if (selector.isBlank()) scope else scope.selectFirst(selector) ?: return null
        return if (attribute != null) el.attr(attribute).takeIf { it.isNotBlank() } else el.text().takeIf { it.isNotBlank() }
    }

    fun domainOf(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").removePrefix("www.").lowercase()
}
