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

    /**
     * What [site] (a domain) offers for [words], as listings of [platform], at a fixed price: page
     * [page] of the answer, inside the price band [min]..[max] where the site's search takes one.
     * Their condition is the site's words where it states one, otherwise [otherwise].
     */
    suspend fun search(site: String, words: String, platform: PlatformId, siteName: String, page: Int = 1, min: Money? = null, max: Money? = null, otherwise: Condition? = Condition.NEW): List<Listing> {
        val band = listOfNotNull(min?.let { "&min_cents=${it.amount}" }, max?.let { "&max_cents=${it.amount}" }).joinToString("")
        val body = Neuland.offers("offers/search?site=$site&q=${words.encodeURLParameter()}&page=$page$band")
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
                oldPrice = o.asking?.money(),
                saleType = SaleType.FIXED_PRICE,
                condition = o.condition?.let(Condition::parse) ?: otherwise,
                imageUrls = listOfNotNull(o.image),
                seller = o.seller?.let { Seller(name = it) },
                shipping = o.shipping?.money()?.let(::delivery),
                scrapedAt = now,
            )
        }
    }

    /**
     * The cars or vans [site] (a domain) offers for [words] and [vehicle]'s conditions, as listings
     * of [platform]: page [page], inside the price band [min]..[max] where the site's search takes
     * one, each with its place, the seller's words, and what its card shows about the vehicle, read
     * by [VehicleTextParser].
     */
    suspend fun searchVehicles(
        site: String, words: String, platform: PlatformId, siteName: String, vehicle: VehicleQuery,
        page: Int = 1, min: Money? = null, max: Money? = null,
    ): List<Listing> {
        val request = kotlinx.serialization.json.buildJsonObject {
            put("site", kotlinx.serialization.json.JsonPrimitive(site))
            put("words", kotlinx.serialization.json.JsonPrimitive(words))
            put("page", kotlinx.serialization.json.JsonPrimitive(page))
            min?.let { put("minCents", kotlinx.serialization.json.JsonPrimitive(it.amount)) }
            max?.let { put("maxCents", kotlinx.serialization.json.JsonPrimitive(it.amount)) }
            put("vehicle", vehicleJson.encodeToJsonElement(VehicleQuery.serializer(), vehicle))
        }
        val answer = try {
            Neuland.request(io.ktor.http.HttpMethod.Post, "offers/search", request, limitS = 150)
        } catch (e: Neuland.Refusal) {
            throw io.github.tieo.arbay.crawler.CrawlerBlockedException("$siteName: ${e.message}", io.github.tieo.arbay.crawler.ErrorType.NETWORK_ERROR)
        }
        val list = runCatching { json.decodeFromJsonElement(OfferList.serializer(), answer) }
            .getOrElse { throw unreadablePage(siteName, "Neuland's answer is not an offer list", answer.toString(), it as? Exception) }
        val now = Clock.System.now()
        return list.offers.map { o ->
            Listing(
                id = "${platform.name}:${o.id}",
                platformId = platform,
                externalId = o.id,
                url = o.url,
                title = o.title,
                price = o.price.money(),
                imageUrls = listOfNotNull(o.image),
                location = o.place?.let { io.github.tieo.arbay.model.Location(city = it.town, zip = it.postcode, country = it.country) },
                description = o.description,
                scrapedAt = now,
                vehicle = o.cardText?.let(io.github.tieo.arbay.crawler.VehicleTextParser::parse),
            )
        }
    }

    private val vehicleJson = Json { encodeDefaults = false; explicitNulls = false }

    /** A car or van search's conditions as Neuland's search takes them; a field not given is not asked. */
    @Serializable
    data class VehicleQuery(
        val make: String? = null,
        val model: String? = null,
        /** "manual" or "automatic". */
        val gearbox: String? = null,
        @kotlinx.serialization.SerialName("registered_from") val registeredFrom: Int? = null,
        @kotlinx.serialization.SerialName("registered_to") val registeredTo: Int? = null,
        @kotlinx.serialization.SerialName("mileage_max_km") val mileageMaxKm: Int? = null,
        @kotlinx.serialization.SerialName("power_min_kw") val powerMinKw: Int? = null,
    )

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
        val condition: String? = null, val asking: NeulandMoney? = null,
        val place: NeulandPlace? = null, val description: String? = null,
        @kotlinx.serialization.SerialName("card_text") val cardText: String? = null,
    )
    @Serializable private data class NeulandPlace(val town: String? = null, val postcode: String? = null, val country: String? = null)
    @Serializable private data class OfferList(val offers: List<NeulandOffer>)
}
