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
                location = o.location(),
                listingDate = o.listedAt(),
                scrapedAt = now,
            )
        }
    }

    /**
     * The cars or vans [site] (a domain) offers for [words] and [vehicle]'s conditions, as listings
     * of [platform]: page [page], inside the price band [min]..[max] where the site's search takes
     * one, each with its place, the seller's words, and the vehicle: as the site states it in fields
     * of its own (verified), or else read off its card's words by [VehicleTextParser].
     */
    suspend fun searchVehicles(
        site: String, words: String, platform: PlatformId, siteName: String, vehicle: VehicleQuery,
        page: Int = 1, min: Money? = null, max: Money? = null, near: io.github.tieo.arbay.crawler.SearchArea? = null,
        otherwise: Condition? = null, countries: List<String> = emptyList(),
    ): List<Listing> {
        val request = kotlinx.serialization.json.buildJsonObject {
            put("site", kotlinx.serialization.json.JsonPrimitive(site))
            put("words", kotlinx.serialization.json.JsonPrimitive(words))
            put("page", kotlinx.serialization.json.JsonPrimitive(page))
            min?.let { put("minCents", kotlinx.serialization.json.JsonPrimitive(it.amount)) }
            max?.let { put("maxCents", kotlinx.serialization.json.JsonPrimitive(it.amount)) }
            put("vehicle", vehicleJson.encodeToJsonElement(VehicleQuery.serializer(), vehicle))
            if (countries.isNotEmpty()) put("countries", kotlinx.serialization.json.JsonArray(countries.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            near?.let { area ->
                put("near", kotlinx.serialization.json.buildJsonObject {
                    area.placeName?.let { put("town", kotlinx.serialization.json.JsonPrimitive(it)) }
                    area.zip?.let { put("postcode", kotlinx.serialization.json.JsonPrimitive(it)) }
                    put("latitude", kotlinx.serialization.json.JsonPrimitive(area.latitude))
                    put("longitude", kotlinx.serialization.json.JsonPrimitive(area.longitude))
                    put("country", kotlinx.serialization.json.JsonPrimitive(area.country))
                    put("radius_km", kotlinx.serialization.json.JsonPrimitive(area.radiusKm))
                })
            }
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
                location = o.location(),
                description = o.description,
                condition = otherwise,
                listingDate = o.listedAt(),
                seller = o.account(),
                scrapedAt = now,
                // What the site states wins and counts as verified; the card's words fill the rest.
                vehicle = io.github.tieo.arbay.crawler.VehicleTextParser.merge(
                    o.vehicle?.info()?.let(io.github.tieo.arbay.crawler.VehicleTextParser::verifiedByPresence),
                    o.cardText?.let(io.github.tieo.arbay.crawler.VehicleTextParser::parse),
                ),
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
        @kotlinx.serialization.SerialName("mileage_min_km") val mileageMinKm: Int? = null,
        @kotlinx.serialization.SerialName("mileage_max_km") val mileageMaxKm: Int? = null,
        @kotlinx.serialization.SerialName("power_min_kw") val powerMinKw: Int? = null,
        @kotlinx.serialization.SerialName("power_max_kw") val powerMaxKw: Int? = null,
        /** "front", "rear" or "all". */
        val drivetrain: String? = null,
        @kotlinx.serialization.SerialName("doors_min") val doorsMin: Int? = null,
        @kotlinx.serialization.SerialName("seats_min") val seatsMin: Int? = null,
        /** Sold by a business (true) or a private person (false). */
        val dealer: Boolean? = null,
        /** Any of these, by Neuland's names ("petrol", "hybrid-diesel"); none is any. */
        val fuels: List<String> = emptyList(),
        /** Any of these bodies, by Neuland's names ("small-car", "transporter"); none is any. */
        val bodies: List<String> = emptyList(),
        /** The least Euro emission class. */
        @kotlinx.serialization.SerialName("emission_min") val emissionMin: Int? = null,
    ) {
        companion object {
            /** A fuel by Neuland's name for it. */
            fun fuel(fuel: io.github.tieo.arbay.model.Fuel) = fuel.name.lowercase().replace('_', '-')

            /** A body by Neuland's name for it. */
            fun body(body: io.github.tieo.arbay.model.BodyType) = body.name.lowercase().replace('_', '-')
        }
    }

    /** What the page of the offer at [url] tells beyond its card. */
    data class Detail(
        /** The seller's text, one line per line they wrote. */
        val description: String?,
        /** The vehicle as the site states it, its fields verified. */
        val vehicle: io.github.tieo.arbay.model.VehicleInfo?,
        /** The page's labelled facts, label and value as the site writes them. */
        val specs: List<Pair<String, String>>,
        /** Where the thing is, where the page says more than the card. */
        val location: io.github.tieo.arbay.model.Location? = null,
        /** Whether the ad is still up: false where the page says it is sold or gone, null where it says neither. */
        val available: Boolean? = null,
        /** When the ad went up, where the page says. */
        val listedAt: kotlin.time.Instant? = null,
        /** How many reviews the seller's rating rests on, where the page says more than the card. */
        val sellerReviews: Int? = null,
    )

    /** What the page of the offer at [url] tells beyond its card, read by Neuland. */
    suspend fun detail(url: String, siteName: String): Detail {
        val body = Neuland.offers("offers/detail?url=${url.encodeURLParameter()}")
        val read = runCatching { json.decodeFromString(NeulandDetail.serializer(), body) }
            .getOrElse { throw unreadablePage(siteName, "Neuland's answer is not an offer's detail", body, it as? Exception) }
        return Detail(
            description = read.description,
            vehicle = read.vehicle?.info()?.let(io.github.tieo.arbay.crawler.VehicleTextParser::verifiedByPresence),
            specs = read.specs.mapNotNull { row -> if (row.size == 2) row[0] to row[1] else null },
            location = read.place?.let { p ->
                io.github.tieo.arbay.model.Location(
                    city = p.town, zip = p.postcode, country = p.country,
                    raw = listOfNotNull(p.postcode, p.town).joinToString(" ").takeIf { it.isNotBlank() },
                )
            },
            available = read.available,
            listedAt = read.listed?.let { runCatching { kotlin.time.Instant.parse(it) }.getOrNull() },
            sellerReviews = read.sellerAccount?.reviews,
        )
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
        val condition: String? = null, val asking: NeulandMoney? = null,
        val place: NeulandPlace? = null, val description: String? = null,
        @kotlinx.serialization.SerialName("card_text") val cardText: String? = null,
        val vehicle: NeulandVehicle? = null,
        /** When it went up, RFC 3339. */
        val listed: String? = null,
        @kotlinx.serialization.SerialName("seller_account") val sellerAccount: NeulandAccount? = null,
    ) {
        /** The seller's account on the market; a private person's name is not kept. */
        fun account() = sellerAccount?.let {
            io.github.tieo.arbay.model.Seller(
                id = it.id,
                type = if (it.dealer) io.github.tieo.arbay.model.SellerType.BUSINESS else io.github.tieo.arbay.model.SellerType.PRIVATE,
                name = it.name, url = it.url, rating = it.stars, reviewCount = it.reviews,
            )
        }
        fun location() = place?.let { io.github.tieo.arbay.model.Location(city = it.town, zip = it.postcode, country = it.country) }
        fun listedAt() = listed?.let { runCatching { kotlin.time.Instant.parse(it) }.getOrNull() }
    }
    @Serializable private data class NeulandVehicle(
        @kotlinx.serialization.SerialName("registered_year") val registeredYear: Int? = null,
        @kotlinx.serialization.SerialName("registered_month") val registeredMonth: Int? = null,
        @kotlinx.serialization.SerialName("mileage_km") val mileageKm: Int? = null,
        @kotlinx.serialization.SerialName("power_kw") val powerKw: Int? = null,
        @kotlinx.serialization.SerialName("displacement_cc") val displacementCc: Int? = null,
        val fuel: String? = null,
        val gearbox: String? = null,
        val drivetrain: String? = null,
        val seats: Int? = null,
        val color: String? = null,
        @kotlinx.serialization.SerialName("emission_class") val emissionClass: Int? = null,
    ) {
        fun info() = io.github.tieo.arbay.model.VehicleInfo(
            firstRegYear = registeredYear,
            firstRegMonth = registeredMonth,
            mileageKm = mileageKm,
            powerKw = powerKw,
            displacementCc = displacementCc,
            fuel = fuel?.let { f -> io.github.tieo.arbay.model.Fuel.entries.firstOrNull { it.name == f.uppercase().replace('-', '_') } },
            gearbox = when (gearbox) { "manual" -> io.github.tieo.arbay.model.Transmission.MANUAL; "automatic" -> io.github.tieo.arbay.model.Transmission.AUTOMATIC; else -> null },
            seats = seats,
            color = color,
            emissionClassEuro = emissionClass,
            drivetrain = when (drivetrain) { "front" -> io.github.tieo.arbay.model.Drivetrain.FWD; "rear" -> io.github.tieo.arbay.model.Drivetrain.RWD; "all" -> io.github.tieo.arbay.model.Drivetrain.AWD; else -> null },
        )
    }
    @Serializable private data class NeulandDetail(
        val description: String? = null, val vehicle: NeulandVehicle? = null, val specs: List<List<String>> = emptyList(),
        val place: NeulandPlace? = null, val available: Boolean? = null, val listed: String? = null,
        @kotlinx.serialization.SerialName("seller_account") val sellerAccount: NeulandAccount? = null,
    )
    @Serializable private data class NeulandAccount(
        val id: String, val dealer: Boolean = false, val name: String? = null, val url: String? = null,
        val stars: Double? = null, val reviews: Int? = null,
    )
    @Serializable private data class NeulandPlace(val town: String? = null, val postcode: String? = null, val country: String? = null)
    @Serializable private data class OfferList(val offers: List<NeulandOffer>)
}
