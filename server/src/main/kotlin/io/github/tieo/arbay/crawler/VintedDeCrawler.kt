package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup

class VintedDeCrawler(private val client: HttpClient) : Crawler {

    /** The product's name off a card, without the fields the title attribute reads out after it. */
    internal fun cardTitle(item: org.jsoup.nodes.Element): String? =
        item.selectFirst("[data-testid*=--overlay-link]")?.attr("title")
            ?.let { cardLabel.split(it).first() }?.trim()?.trimEnd(',')?.takeIf { it.isNotBlank() }

    /** Where a Vinted card stops naming the thing and starts describing it: a labelled field, in
     *  any of the languages it runs in. */
    private val cardLabel = Regex(
        """,\s*(marke|zustand|gr(ö|oe)(ß|ss)e|brand|condition|size|marque|(é|e)tat|taille|""" +
            """marca|condizione|talla|taglia|merk|staat|maat)\s*:""",
        RegexOption.IGNORE_CASE,
    )

    override val platformId = PlatformId.VINTED_DE

    override suspend fun search(query: SearchQuery): List<Listing> {
        val url = "https://www.vinted.de/catalog?search_text=${query.positiveText.encodeUrl()}"
        val html = fetchWithFallback(
            client, url, "Vinted",
            waitSelector = "[data-testid=grid-item]",
        )
        return parseSearchResults(html)
    }

    private fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        val items = doc.select("[data-testid=grid-item]")
        if (items.isEmpty()) return emptyList()

        return items.mapNotNull { item ->
            val linkEl = item.selectFirst("a[href*='/items/']") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            val url = if (href.startsWith("http")) href else "https://www.vinted.de$href"

            val externalId = Regex("""/items/(\d+)""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null

            val priceEl = item.selectFirst("[data-testid*=--price-text]")
            val priceText = priceEl?.text() ?: return@mapNotNull null
            val price = Money.parse(priceText) ?: return@mapNotNull null

            // The overlay link's title attribute is the whole card read aloud: "Sony WH-1000XM5,
            // Marke: Sony, Zustand: Sehr gut, 50,00 €, 53,20 €". Everything from the first labelled
            // field on describes the listing rather than naming it, and the labels are capitalised
            // and in the language of whichever Vinted this is — cutting at a lowercase ", marke:"
            // matched none of them, so the price ended up inside the product's name.
            val overlayTitle = cardTitle(item)
            // Fallback to description-title (often just brand name like "Sony")
            val fallbackTitle = item.selectFirst("[data-testid*=--description-title]")?.text()?.trim()
            val title = overlayTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null

            val conditionText = item.selectFirst("[data-testid*=--description-subtitle]")?.text()
            val condition = conditionText?.let { Condition.parse(it) }

            val imageUrl = item.selectFirst("[data-testid*=--image--img]")?.attr("src")
                ?: item.selectFirst("img")?.attr("src")

            // Vinted mandatory fees baked into price: buyer protection 5% (min €0.70) + service fee €0.70
            val buyerProtection = maxOf(price.amount * 5 / 100, 70L)
            val serviceFee = 70L
            val priceWithFees = Money(price.amount + buyerProtection + serviceFee, price.currency)

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = priceWithFees,
                oldPrice = price, // show original price as reference
                condition = condition,
                imageUrls = listOfNotNull(imageUrl),
                // Shipping varies by weight — not known from search results
                scrapedAt = now,
            )
        }
    }
}
