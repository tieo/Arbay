package io.github.tieo.arbay.neuland

import io.github.tieo.arbay.markets.unreadablePage
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

    /** What a refusal from Neuland means for the site: the site refusing (its status, or a browser's
     *  read ending on its error page or a block) starts the site's cooldown; Neuland not answering,
     *  as while it restarts for a deploy, is no word from the site. */
    private fun Neuland.Refusal.errorType(): io.github.tieo.arbay.markets.ErrorType {
        val status = answer["status"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
        val state = (answer["state"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        return when {
            this.status == 0 -> io.github.tieo.arbay.markets.ErrorType.NETWORK_ERROR
            status == 429 -> io.github.tieo.arbay.markets.ErrorType.RATE_LIMITED_429
            status == 403 || state == "errorpage" || state == "refused" -> io.github.tieo.arbay.markets.ErrorType.BLOCKED_403
            status == 503 -> io.github.tieo.arbay.markets.ErrorType.SERVICE_UNAVAILABLE_503
            else -> io.github.tieo.arbay.markets.ErrorType.NETWORK_ERROR
        }
    }

    /** One page of a site's answer as Arbay keeps it: its listings, the searches the site lists as
     *  related, and the conditions its own search was asked. */
    data class Answer(val listings: List<Listing>, val related: List<String>, val asked: List<String>)

    /**
     * What [site] (a domain) offers for [words], as listings of [platform]: page [page] of the
     * answer, inside the price band [min]..[max] where the site's search takes one, a car or van's
     * [vehicle] conditions and the place [near] where given, in [countries] for a site serving
     * several. Their condition is the site's words where it states one, otherwise [otherwise]; only
     * the [kinds] asked for, where the site files its offers by kind. A vehicle is as the site states
     * it in fields of its own (verified), its card's words read by [VehicleTextParser] for the rest.
     */
    suspend fun ask(
        site: String, words: String, platform: PlatformId, siteName: String, page: Int = 1, min: Money? = null, max: Money? = null,
        vehicle: VehicleQuery? = null, near: io.github.tieo.arbay.markets.SearchArea? = null, countries: List<String> = emptyList(),
        otherwise: Condition? = null, kinds: Set<String>? = null,
        sold: Boolean = false, conditions: List<String> = emptyList(), perPage: Int? = null,
    ): Answer {
        val request = kotlinx.serialization.json.buildJsonObject {
            put("site", kotlinx.serialization.json.JsonPrimitive(site))
            put("words", kotlinx.serialization.json.JsonPrimitive(words))
            put("page", kotlinx.serialization.json.JsonPrimitive(page))
            min?.let { put("minCents", kotlinx.serialization.json.JsonPrimitive(it.amount)) }
            max?.let { put("maxCents", kotlinx.serialization.json.JsonPrimitive(it.amount)) }
            vehicle?.let { put("vehicle", vehicleJson.encodeToJsonElement(VehicleQuery.serializer(), it)) }
            if (sold) put("sold", kotlinx.serialization.json.JsonPrimitive(true))
            if (conditions.isNotEmpty()) put("conditions", kotlinx.serialization.json.JsonArray(conditions.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            perPage?.let { put("perPage", kotlinx.serialization.json.JsonPrimitive(it)) }
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
            throw io.github.tieo.arbay.markets.CrawlerBlockedException("$siteName: ${e.message}", e.errorType())
        }
        val list = runCatching { json.decodeFromJsonElement(OfferList.serializer(), answer) }
            .getOrElse { throw unreadablePage(siteName, "Neuland's answer is not an offer list", answer.toString(), it as? Exception) }
        val now = Clock.System.now()
        val listings = list.offers.filter { o -> kinds == null || o.kind == null || o.kind in kinds }.map { o ->
            val auctionOnly = o.auction?.let { !it.buyNow } == true
            Listing(
                id = "${platform.name}:${o.id}",
                platformId = platform,
                externalId = o.id,
                url = o.url,
                title = o.title,
                price = o.price.money(),
                priceUpTo = o.priceUpTo?.money(),
                sold = o.sold,
                soldDate = o.ended?.let { runCatching { kotlin.time.Instant.parse(it) }.getOrNull() },
                oldPrice = o.asking?.money(),
                negotiable = o.negotiable,
                saleType = if (auctionOnly) SaleType.AUCTION else SaleType.FIXED_PRICE,
                auctionEndsAt = o.auction?.ends?.takeIf { auctionOnly }?.let { runCatching { kotlin.time.Instant.parse(it) }.getOrNull() },
                bidCount = o.auction?.bids,
                condition = o.condition?.let(Condition::parse) ?: otherwise,
                imageUrls = listOfNotNull(o.image),
                seller = o.account() ?: o.seller?.let { Seller(name = it) },
                shipping = o.shipping?.money()?.let(::delivery) ?: when (o.ships) {
                    true -> Shipping(available = true)
                    false -> Shipping(pickup = true)
                    null -> null
                },
                location = o.location(),
                listingDate = o.listedAt(),
                description = o.description,
                scrapedAt = now,
                vehicle = if (vehicle == null) o.vehicle?.info()?.let(io.github.tieo.arbay.relevance.VehicleTextParser::verifiedByPresence)
                else io.github.tieo.arbay.relevance.VehicleTextParser.merge(
                    o.vehicle?.info()?.let(io.github.tieo.arbay.relevance.VehicleTextParser::verifiedByPresence),
                    o.cardText?.let(io.github.tieo.arbay.relevance.VehicleTextParser::parse),
                ),
            )
        }
        return Answer(listings, list.related, list.asked)
    }

    /** What [site] offers for [words] (see [ask]), its condition [otherwise] where it states none. */
    suspend fun search(
        site: String, words: String, platform: PlatformId, siteName: String, page: Int = 1, min: Money? = null, max: Money? = null,
        otherwise: Condition? = Condition.NEW, kinds: Set<String>? = null,
    ): List<Listing> = ask(site, words, platform, siteName, page, min, max, otherwise = otherwise, kinds = kinds).listings

    /** The cars or vans [site] offers for [words] and [vehicle]'s conditions (see [ask]). */
    suspend fun searchVehicles(
        site: String, words: String, platform: PlatformId, siteName: String, vehicle: VehicleQuery,
        page: Int = 1, min: Money? = null, max: Money? = null, near: io.github.tieo.arbay.markets.SearchArea? = null,
        otherwise: Condition? = null, countries: List<String> = emptyList(),
    ): List<Listing> = ask(site, words, platform, siteName, page, min, max, vehicle, near, countries, otherwise).listings

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
        /** Among vans rather than cars, where a site files them apart. */
        val vans: Boolean = false,
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
        /** Promised to a buyer or taken off by its seller for now, and may come back. */
        val paused: Boolean = false,
        /** When the ad went up, where the page says. */
        val listedAt: kotlin.time.Instant? = null,
        /** How many reviews the seller's rating rests on, where the page says more than the card. */
        val sellerReviews: Int? = null,
        /** Every photo of the gallery, largest copies. */
        val photos: List<String> = emptyList(),
        /** Who sells it, where the page names them. */
        val seller: io.github.tieo.arbay.model.SellerProfile? = null,
        val title: String? = null,
        /** The price asked on the page now. */
        val price: Money? = null,
        /** The shipping the page offers: its cheapest cost, or only that it ships, or pickup only. */
        val shipping: Shipping? = null,
        /** What the site files it as, in its own key. */
        val kind: String? = null,
        /** How many are left, where the page says; [moreInStock] where that is a floor. */
        val stock: Int? = null,
        val moreInStock: Boolean = false,
        /** What the seller says of the condition, apart from the description. */
        val conditionNote: String? = null,
        val coupon: io.github.tieo.arbay.model.Coupon? = null,
    )

    /** What the page of the offer at [url] tells beyond its card, read by Neuland. */
    suspend fun detail(url: String, siteName: String): Detail {
        val body = Neuland.offers("offers/detail?url=${url.encodeURLParameter()}")
        val read = runCatching { json.decodeFromString(NeulandDetail.serializer(), body) }
            .getOrElse { throw unreadablePage(siteName, "Neuland's answer is not an offer's detail", body, it as? Exception) }
        return Detail(
            description = read.description,
            vehicle = read.vehicle?.info()?.let(io.github.tieo.arbay.relevance.VehicleTextParser::verifiedByPresence),
            specs = read.specs.mapNotNull { row -> if (row.size == 2) row[0] to row[1] else null },
            location = read.place?.let { p ->
                io.github.tieo.arbay.model.Location(
                    city = p.town, zip = p.postcode, country = p.country,
                    raw = listOfNotNull(p.postcode, p.town).joinToString(" ").takeIf { it.isNotBlank() },
                )
            },
            available = read.available,
            paused = read.paused,
            listedAt = read.listed?.let { runCatching { kotlin.time.Instant.parse(it) }.getOrNull() },
            sellerReviews = read.sellerAccount?.reviews,
            photos = read.photos,
            seller = read.sellerAccount?.let { a ->
                io.github.tieo.arbay.model.SellerProfile(
                    name = a.name,
                    type = if (a.dealer) io.github.tieo.arbay.model.SellerType.BUSINESS else io.github.tieo.arbay.model.SellerType.PRIVATE,
                    since = a.since?.let { runCatching { kotlinx.datetime.LocalDate.parse(it) }.getOrNull() },
                    badges = a.badges,
                )
            },
            title = read.title,
            price = read.price?.money(),
            shipping = read.shipping?.money()?.let { Shipping(cost = it, free = it.amount == 0L, pickup = read.pickup, available = true) } ?: when (read.ships) {
                true -> Shipping(available = true, pickup = read.pickup)
                false -> Shipping(available = false, pickup = read.pickup)
                null -> if (read.pickup) Shipping(available = false, pickup = true) else null
            },
            kind = read.kind,
            stock = read.stock,
            moreInStock = read.moreInStock,
            conditionNote = read.conditionNote,
            coupon = read.coupon?.let { io.github.tieo.arbay.model.Coupon(it.price.money(), it.code, it.terms) },
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
        val auction: NeulandAuction? = null,
        /** What the site files it as, in its own key. */
        val kind: String? = null,
        val negotiable: Boolean = false,
        /** Ships at a cost the card leaves open (true), or is collected only (false). */
        val ships: Boolean? = null,
        @kotlinx.serialization.SerialName("price_up_to") val priceUpTo: NeulandMoney? = null,
        val sold: Boolean = false,
        val ended: String? = null,
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
    /**
     * A vehicle the site calls new, as its mileage bears out. The condition is the seller's pick,
     * and a dealer feed on Marktplaats ticks "Nieuw" on every van it lists: Crafters of 2019 to
     * 2024 with 130,000 to 208,000 km went out of a search for used vans as new. Driven past a demo
     * car's mileage it is used; past a delivery run, whether it is new or a demonstrator is not known.
     */
    internal fun drivenOrNew(mileageKm: Int?): io.github.tieo.arbay.model.VehicleCondition? = when {
        mileageKm == null || mileageKm <= NEW_MAX_KM -> io.github.tieo.arbay.model.VehicleCondition.NEW
        mileageKm > DEMO_MAX_KM -> io.github.tieo.arbay.model.VehicleCondition.USED
        else -> null
    }
    private const val NEW_MAX_KM = 1_000
    private const val DEMO_MAX_KM = 20_000

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
        val condition: String? = null,
        val doors: Int? = null,
        @kotlinx.serialization.SerialName("wheelbase_mm") val wheelbaseMm: Int? = null,
        @kotlinx.serialization.SerialName("emission_sticker") val emissionSticker: Int? = null,
        val body: String? = null,
        val upholstery: String? = null,
        @kotlinx.serialization.SerialName("inspection_until") val inspectionUntil: String? = null,
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
            emissionSticker = emissionSticker,
            bodyType = body?.let { b -> io.github.tieo.arbay.model.BodyType.entries.firstOrNull { it.name == b.uppercase().replace('-', '_') } },
            upholstery = upholstery,
            inspectionUntil = inspectionUntil,
            doors = doors,
            wheelbaseMm = wheelbaseMm,
            condition = when (condition) {
                "new" -> drivenOrNew(mileageKm)
                "used" -> io.github.tieo.arbay.model.VehicleCondition.USED
                "pre-registered" -> io.github.tieo.arbay.model.VehicleCondition.PRE_REGISTRATION
                "damaged" -> io.github.tieo.arbay.model.VehicleCondition.DAMAGED
                else -> null
            },
            drivetrain = when (drivetrain) { "front" -> io.github.tieo.arbay.model.Drivetrain.FWD; "rear" -> io.github.tieo.arbay.model.Drivetrain.RWD; "all" -> io.github.tieo.arbay.model.Drivetrain.AWD; else -> null },
        )
    }
    @Serializable private data class NeulandDetail(
        val description: String? = null, val vehicle: NeulandVehicle? = null, val specs: List<List<String>> = emptyList(),
        val place: NeulandPlace? = null, val available: Boolean? = null, val paused: Boolean = false, val listed: String? = null,
        @kotlinx.serialization.SerialName("seller_account") val sellerAccount: NeulandAccount? = null,
        val photos: List<String> = emptyList(), val title: String? = null, val price: NeulandMoney? = null,
        val shipping: NeulandMoney? = null, val ships: Boolean? = null, val kind: String? = null,
        val pickup: Boolean = false, val stock: Int? = null,
        @kotlinx.serialization.SerialName("more_in_stock") val moreInStock: Boolean = false,
        @kotlinx.serialization.SerialName("condition_note") val conditionNote: String? = null,
        val coupon: NeulandCoupon? = null,
    )
    @Serializable private data class NeulandCoupon(val price: NeulandMoney, val code: String? = null, val terms: String? = null)
    @Serializable private data class NeulandAuction(
        @kotlinx.serialization.SerialName("buy_now") val buyNow: Boolean = false,
        val bid: NeulandMoney? = null, val bids: Int? = null, val ends: String? = null,
    )
    @Serializable private data class NeulandAccount(
        val id: String, val dealer: Boolean = false, val name: String? = null, val url: String? = null,
        val stars: Double? = null, val reviews: Int? = null, val since: String? = null, val badges: List<String> = emptyList(),
    )
    @Serializable private data class NeulandPlace(val town: String? = null, val postcode: String? = null, val country: String? = null)
    @Serializable private data class OfferList(val offers: List<NeulandOffer>, val related: List<String> = emptyList(), val asked: List<String> = emptyList())
}
