package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlin.time.Clock
import org.jsoup.Jsoup

class VintedDeCrawler(private val client: HttpClient) : Crawler, StartsAtAnyPage, KnowsAdState {

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

    /** One page of Vinted's catalog, read by Neuland, inside Vinted's own price band where the
     *  search has one: a search for the cheapest of a thing sold by the hundred reads only the pages
     *  inside the band. A card's price is what the buyer pays with Vinted's fee, the seller's own
     *  beside it. */
    private suspend fun searchPage(query: SearchQuery, page: Int): List<Listing> =
        NeulandOffers.search("vinted.de", query.positiveText, platformId, "Vinted", page, query.minPrice, query.maxPrice, otherwise = null)

}
