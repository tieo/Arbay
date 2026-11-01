package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.http.encodeURLParameter
import kotlin.time.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Idealo, read by Neuland's shop reader: its products, each priced by the shop cheapest to have
 * delivered and carrying every shop, and its single offers of one shop with that shop's delivery
 * charge. The shop with a product's lowest item price is often not the cheapest delivered:
 * alza.de's 11,49 € for the Cudy GS108U costs 16,48 € with delivery, beside Amazon's 17,90 €.
 */
class IdealoCrawler : Crawler {
    override val platformId = PlatformId.IDEALO

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val body = Neuland.shop("shops/idealo?q=${query.positiveText.encodeURLParameter()}")
        val list = runCatching { json.decodeFromString(OfferList.serializer(), body) }
            .getOrElse { throw unreadablePage("Idealo", "Neuland's answer is not an offer list", body, it as? Exception) }
        val now = Clock.System.now()
        val listings = list.offers.map { o ->
            Listing(
                id = "${platformId.name}:${o.id}",
                platformId = platformId,
                externalId = o.id,
                url = o.url,
                title = o.title.tidyTitle(),
                price = o.price.money(),
                saleType = SaleType.FIXED_PRICE,
                condition = Condition.NEW,
                imageUrls = listOfNotNull(o.image),
                seller = o.seller?.let { Seller(name = it) },
                shipping = o.shipping?.money()?.let { if (it.amount == 0L) Shipping(cost = it, free = true) else Shipping(cost = it) },
                scrapedAt = now,
            )
        }
        // Idealo answers a word for a kind of thing ("laptop") from its own category, whose products
        // carry the category's name rather than the search's words.
        if (listings.isNotEmpty() && listings.none { it.title.contains(query.positiveText, ignoreCase = true) }) {
            emitAnsweredFromCategory()
        }
        return listings
    }

    /** A product's every shop, cheapest delivered first, read anew; a single offer has none. */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? {
        val product = PRODUCT.find(listing.url)?.groupValues?.get(1) ?: return null
        val shops = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(NeulandShop.serializer()), Neuland.shop("shops/idealo/products/$product"))
            .map { ShopOffer(it.shop, it.price.money(), it.shipping?.money(), it.url) }
        val cheapest = shops.firstOrNull { it.shipping != null }
        return ListingDetail(
            shops = shops,
            shipping = cheapest?.shipping?.let { if (it.amount == 0L) Shipping(cost = it, free = true) else Shipping(cost = it) },
        )
    }

    @Serializable private data class NeulandMoney(val cents: Long, val currency: String) {
        fun money() = Money(cents, Currency.entries.firstOrNull { it.name == currency } ?: Currency.EUR)
    }
    @Serializable private data class NeulandShop(val shop: String, val price: NeulandMoney, val shipping: NeulandMoney? = null, val url: String? = null)
    @Serializable private data class NeulandOffer(
        val id: String, val title: String, val url: String, val image: String? = null,
        val price: NeulandMoney, val shipping: NeulandMoney? = null, val seller: String? = null,
    )
    @Serializable private data class OfferList(val offers: List<NeulandOffer>)

    private companion object {
        val PRODUCT = Regex("""/OffersOfProduct/(\d+)_""")
    }
}
