package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.neuland.MarketAccount
import io.github.tieo.arbay.neuland.NeulandOffers
import io.github.tieo.arbay.neuland.NeulandSign
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * One of eBay's sites ([domain]: ebay.de, .com, .it, .fr, .es), read by Neuland: its search
 * cheapest first, page after page until the search has as many offers as it wants or a page comes
 * back short, and an item's own page. Sold offers show only to a signed-in browser, the user's own
 * account in Neuland's "accounts" browser.
 */
class EbayDeCrawler(
    override val platformId: PlatformId = PlatformId.EBAY_DE,
    private val domain: String = "ebay.de",
    /** One page of the site's answer to a search; Neuland's reader of it. */
    private val page: suspend (EbayDeCrawler, SearchQuery, Int) -> List<Listing> = { crawler, query, number -> crawler.neulandPage(query, number) },
) : Crawler, FetchesEveryPage, HasSoldListings, SellsByAuction, ReadsGalleries {

    override suspend fun search(query: SearchQuery): List<Listing> {
        val first = try {
            page(this, query, 1)
        } catch (e: CrawlerBlockedException) {
            if (query.soldOnly) askSignInWhereSignedOut()
            throw e
        }
        val seen = mutableSetOf<String>()
        val results = first.filter { seen.add(it.externalId) }.toMutableList()
        // Each page streams as it is read, which is also what tells the crawl's time limit that eBay
        // is answering: without it the limit ran over the whole search and its other words, and
        // cut eBay off at 180 s while it answered every few seconds.
        emitPartialResults(results)
        var last = first
        var number = 1
        while (last.size >= 20 && results.size < query.resultCap() && number < query.pageLimit()) {
            number++
            last = try { page(this, query, number) } catch (e: CancellationException) { throw e } catch (_: CrawlerBlockedException) { break }
            val fresh = last.filter { seen.add(it.externalId) }
            if (fresh.isEmpty()) break
            results += fresh
            emitPartialResults(fresh)
        }
        return results
    }

    /**
     * Page [number] of [query] as eBay answers it. A car on ebay.de is searched by its model alone:
     * sellers title vans "VW Crafter", not "Volkswagen Crafter", so the make's full name matches
     * nothing. No category is asked: eBay answers a search in its vehicle categories with 403 and
     * never narrowed it to whole vehicles anyway; the vehicle post-filter removes the parts.
     */
    internal suspend fun neulandPage(query: SearchQuery, number: Int): List<Listing> {
        val car = if (domain == "ebay.de") CarQueryResolver.resolve(query.positiveText) else null
        val words = car?.modelSlug?.replace("-", " ") ?: query.text
        // eBay lists up to 240 offers a page and is slow to serve its later pages: a search wanting
        // more than one page of the usual size asks for the largest one.
        val perPage = if ((query.maxResults ?: 0) > CrawlerConfig.current.ebayItemsPerPage) 240 else CrawlerConfig.current.ebayItemsPerPage
        val found = NeulandOffers.ask(
            domain, words, platformId, "eBay", number, query.minPrice, query.maxPrice,
            // eBay's own radius search: its cards publish no town, so an area has to be asked at the source.
            near = query.area(platformId.country ?: "DE"),
            // ebay.com is asked for what ships to Germany.
            countries = if (domain == "ebay.com") listOf("DE") else emptyList(),
            sold = query.soldOnly,
            conditions = query.condition.orEmpty().mapNotNull(::conditionWord),
            perPage = perPage,
        ).listings
        return if (query.soldOnly) found.map { it.copy(sold = true) } else found
    }

    /** A search for sold offers that eBay refused may be the account signed out; the user is asked
     *  to sign in again only where the account says so. */
    private suspend fun askSignInWhereSignedOut() {
        val standing = runCatching { MarketAccount(NeulandSign.BROWSER, "ebay.de").standing() }.getOrNull() ?: return
        if (!standing.signedIn) {
            log.info("{}: the account browser is signed out of eBay", domain)
            io.github.tieo.arbay.chat.Chat.askSignIn(SignInLink(PlatformId.EBAY_DE))
        }
    }

    /**
     * What eBay keeps on the item page and nowhere on the card: the vehicle's specifics, where the
     * thing is (town and country, never a postcode), the seller's whole text, how many are left,
     * the delivery options, the seller's condition note and a coupon's price.
     */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        val page = NeulandOffers.detail(listing.url, "eBay")
        ListingDetail(
            vehicle = page.vehicle,
            description = page.description,
            location = page.location,
            sellerReviews = page.sellerReviews,
            available = page.stock,
            moreAvailable = page.moreInStock,
            photos = page.photos,
            shipping = page.shipping,
            conditionNote = page.conditionNote,
            coupon = page.coupon,
        ).takeIf { it.vehicle != null || it.location != null || it.description != null || it.photos.isNotEmpty() }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        log.debug("{}: item page unread for {}: {}", domain, listing.url, e.message?.take(80))
        null
    }

    /** A condition as Neuland names it for a site's search; none for plain "used", which eBay files
     *  under its grades. */
    private fun conditionWord(condition: Condition): String? = when (condition) {
        Condition.NEW -> "new"
        Condition.LIKE_NEW -> "like-new"
        Condition.VERY_GOOD -> "very-good"
        Condition.GOOD -> "good"
        Condition.ACCEPTABLE -> "acceptable"
        Condition.REFURBISHED -> "refurbished"
        Condition.PARTS_ONLY -> "parts"
        Condition.USED -> null
    }

    private companion object {
        val log = LoggerFactory.getLogger(EbayDeCrawler::class.java)
    }
}
