package io.github.tieo.arbay.neuland

import io.github.tieo.arbay.crawler.unreadablePage
import io.github.tieo.arbay.model.Condition
import io.github.tieo.arbay.model.Currency
import io.github.tieo.arbay.model.Listing
import io.github.tieo.arbay.model.Money
import io.github.tieo.arbay.model.PlatformId
import io.github.tieo.arbay.model.SaleType
import io.github.tieo.arbay.model.Seller
import io.github.tieo.arbay.model.SellerOffer
import io.github.tieo.arbay.model.Shipping
import io.ktor.http.encodeURLParameter
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Shops, markets and price comparisons Neuland reads into offers (Idealo, Geizhals): a site's
 * search and an offer's sellers, as Arbay's listings.
 */
object NeulandOffers {
    private val json = Json { ignoreUnknownKeys = true }

    /** What [site] (a domain) offers for [words], as listings of [platform], new and at a fixed price. */
    suspend fun search(site: String, words: String, platform: PlatformId, siteName: String): List<Listing> {
        val body = Neuland.offers("offers/search?site=$site&q=${words.encodeURLParameter()}")
        val list = runCatching { json.decodeFromString(OfferList.serializer(), body) }
            .getOrElse { throw unreadablePage(siteName, "Neuland's answer is not an offer list", body, it as? Exception) }
        val now = Clock.System.now()
        return list.offers.map { o ->
            Listing(
                id = "${platform.name}:${o.id}",
                platformId = platform,
                externalId = o.id,
                url = o.url,
                title = o.title,
                price = o.price.money(),
                saleType = SaleType.FIXED_PRICE,
                condition = Condition.NEW,
                imageUrls = listOfNotNull(o.image),
                seller = o.seller?.let { Seller(name = it) },
                shipping = o.shipping?.money()?.let(::delivery),
                scrapedAt = now,
            )
        }
    }

    /** Every seller of the offer at [url], cheapest delivered first; none where the site lists none. */
    suspend fun sellers(url: String): List<SellerOffer> =
        json.decodeFromString(ListSerializer(NeulandSeller.serializer()), Neuland.offers("offers/sellers?url=${url.encodeURLParameter()}"))
            .map { SellerOffer(it.seller, it.price.money(), it.shipping?.money(), it.url) }

    /** A delivery charge as Arbay's shipping, free where it is nothing. */
    fun delivery(cost: Money) = if (cost.amount == 0L) Shipping(cost = cost, free = true) else Shipping(cost = cost)

    @Serializable private data class NeulandMoney(val cents: Long, val currency: String) {
        fun money() = Money(cents, Currency.entries.firstOrNull { it.name == currency } ?: Currency.EUR)
    }
    @Serializable private data class NeulandSeller(val seller: String, val price: NeulandMoney, val shipping: NeulandMoney? = null, val url: String? = null)
    @Serializable private data class NeulandOffer(
        val id: String, val title: String, val url: String, val image: String? = null,
        val price: NeulandMoney, val shipping: NeulandMoney? = null, val seller: String? = null,
    )
    @Serializable private data class OfferList(val offers: List<NeulandOffer>)
}
