package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlin.time.Clock
import org.jsoup.Jsoup

class VintedDeCrawler(private val client: HttpClient) : Crawler, StartsAtAnyPage, KnowsAdState {

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

    /** The seller's text off the item page, which the catalog card does not carry. */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        parseDetail(fetchWithFallback(
            client, listing.url.substringBefore("?"), "Vinted",
            waitSelector = "[itemprop=description]", stealthMarker = "itemprop=\"description\"",
        ))
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        null
    }

    internal fun parseDetail(html: String): ListingDetail? =
        Jsoup.parse(html).selectFirst("[itemprop=description]")?.wholeText()
            ?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }
            ?.let { ListingDetail(description = it) }

    override suspend fun adState(url: String): AdState? = try {
        adStateOf(fetchWithFallback(client, url.substringBefore("?"), "Vinted"))
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
        null
    }

    /**
     * An item page's state, read off the item the page's script carries. A sold item keeps its page
     * and its price, and only a status box ("Verkauft") and a buy button that is gone tell it apart
     * from one on offer; a reserved or hidden item is promised to a buyer or taken off by its seller,
     * and may come back.
     */
    internal fun adStateOf(html: String): AdState? {
        val page = html.replace("\\\"", "\"")
        return when {
            "\"is_reserved\":true" in page || "\"is_hidden\":true" in page -> AdState.PAUSED
            "\"name\":\"buyer_item_status\"" in page -> AdState.DELETED
            "\"can_buy\":true" in page -> AdState.LIVE
            else -> null
        }
    }

    /** One page of the catalog, unless the search asked for more offers than usual: each page
     *  is a fetch Vinted counts towards its challenge, so the rest are read only when wanted. */
    override suspend fun search(query: SearchQuery): List<Listing> =
        if (query.maxResults == null) searchPage(query, query.startPage) else paginate(query) { searchPage(query, it) }

    private suspend fun searchPage(query: SearchQuery, page: Int): List<Listing> {
        // Vinted's own price band, in euro: a search for the cheapest of a thing sold by the
        // hundred reads only the pages inside the band instead of every page of it.
        val url = "https://www.vinted.de/catalog?search_text=${query.positiveText.encodeUrl()}" +
            (query.minPrice?.let { "&price_from=${it.amount / 100}" } ?: "") +
            (query.maxPrice?.let { "&price_to=${it.amount / 100}" } ?: "") +
            (if (query.minPrice != null || query.maxPrice != null) "&currency=EUR" else "") +
            (if (page > 1) "&page=$page" else "")
        val html = fetchWithFallback(
            client, url, "Vinted",
            waitSelector = "[data-testid=grid-item]",
            // After a burst of searches Vinted answers every engine with Cloudflare's challenge.
            stealthMarker = "data-testid=\"grid-item\"", stealthMinMatches = 3,
        )
        return parseSearchResults(html)
    }

    internal fun parseSearchResults(html: String): List<Listing> {
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

            // What the buyer pays: the card's own total with Vinted's fee ("11,20 € inklusive
            // Vinted-Gebühr"), the price the seller asks beside it.
            val priceWithFees = item.selectFirst("[data-testid=total-combined-price]")?.text()
                ?.let { Money.parseAtMarket(it, price.currency) } ?: price

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = priceWithFees,
                oldPrice = price.takeIf { it != priceWithFees },
                condition = condition,
                imageUrls = listOfNotNull(imageUrl),
                // Shipping varies by weight — not known from search results
                scrapedAt = now,
            )
        }
    }
}
