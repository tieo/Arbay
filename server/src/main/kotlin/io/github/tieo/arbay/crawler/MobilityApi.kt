package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*

/**
 * Schibsted's "mobility" used-car platform powers dba.dk (DK), finn.no (NO) and its siblings with a
 * single JSON search API — /mobility/search/api/search/SEARCH_ID_CAR_USED returning `docs[]` — and
 * one native URL filter scheme (year_from/to, mileage_to, engine_effect_from in hp, price_from/to,
 * transmission). This shared helper builds the filtered request and parses the docs so each national
 * crawler is only a host + currency, and every one of them filters at the source for free.
 */
object MobilityApi {
    private val json = Json { ignoreUnknownKeys = true }
    private fun kwToHp(kw: Int): Int = (kw * 1.35962).toInt()

    /** The filtered search-API URL for [host] (e.g. "www.finn.no"). Price bounds are converted to
     *  [priceCurrency], the currency that host lists in. */
    fun searchUrl(host: String, text: String, query: SearchQuery, page: Int, priceCurrency: Currency): String = buildString {
        append("https://$host/mobility/search/api/search/SEARCH_ID_CAR_USED")
        append("?q=").append(text.encodeUrl())
        query.carCriteria.firstRegFromYear?.let { append("&year_from=$it") }
        query.carCriteria.firstRegToYear?.let { append("&year_to=$it") }
        query.carCriteria.maxMileageKm?.let { append("&mileage_to=$it") }
        query.carCriteria.minPowerKw?.let { append("&engine_effect_from=${kwToHp(it)}") }
        query.maxPrice?.let { append("&price_to=${inCurrency(it, priceCurrency)}") }
        query.minPrice?.let { append("&price_from=${inCurrency(it, priceCurrency)}") }
        when (query.carCriteria.transmission) {
            Transmission.AUTOMATIC -> append("&transmission=2")
            Transmission.MANUAL -> append("&transmission=1")
            null -> {}
        }
        if (page > 1) append("&page=$page")
    }

    private fun inCurrency(money: Money, cur: Currency): Long =
        if (money.currency == cur) money.amount / 100
        else ExchangeRates.convert(money.amount, money.currency.name, cur.name) / 100

    /** Parse a mobility search-API response. Each doc carries id, heading, canonical_url,
     *  price.amount, location, year, mileage, and fuel/transmission; the id matches the HTML page's,
     *  so externalIds stay stable across code paths. Returns null on a non-JSON/blocked body. */
    fun parseDocs(body: String, platformId: PlatformId, currency: Currency, country: String, itemHost: String): List<Listing>? {
        val now = Clock.System.now()
        return try {
            val docs = json.parseToJsonElement(body).jsonObject["docs"]?.jsonArray ?: return null
            docs.mapNotNull { el ->
                val doc = el.jsonObject
                val externalId = doc["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val title = doc["heading"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val url = doc["canonical_url"]?.jsonPrimitive?.contentOrNull
                    ?: "https://$itemHost/mobility/item/$externalId"
                val priceAmount = doc["price"]?.jsonObject?.get("amount")?.jsonPrimitive?.longOrNull
                    ?.takeIf { it >= 0 } ?: return@mapNotNull null // amount is already in the major unit
                val imageUrl = doc["image"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                val city = doc["location"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                val year = doc["year"]?.jsonPrimitive?.intOrNull
                val mileage = doc["mileage"]?.jsonPrimitive?.longOrNull

                val description = buildString {
                    year?.let { append(it) }
                    mileage?.let { if (isNotEmpty()) append(" | "); append("$it km") }
                }.takeIf { it.isNotBlank() }

                val vehicle = VehicleTextParser.verifiedByPresence(VehicleInfo(
                    firstRegYear = year?.takeIf { it in 1980..2035 },
                    mileageKm = mileage?.takeIf { it in 1..2_000_000 }?.toInt(),
                    fuel = Fuel.parse((doc["fuel_type"] ?: doc["fuel"] ?: doc["propellant"])?.jsonPrimitive?.contentOrNull),
                    gearbox = when ((doc["transmission"] ?: doc["gear"])?.jsonPrimitive?.contentOrNull?.lowercase()) {
                        "automatic", "automatisk", "automatgear", "automat" -> Transmission.AUTOMATIC
                        "manual", "manuel", "manuelt", "manuell" -> Transmission.MANUAL
                        else -> null
                    },
                ))

                Listing(
                    id = "${platformId.name}:$externalId",
                    platformId = platformId,
                    externalId = externalId,
                    url = url,
                    title = title,
                    price = Money(priceAmount * 100, currency),
                    imageUrls = listOfNotNull(imageUrl),
                    location = Location(city = city, country = country),
                    description = description,
                    scrapedAt = now,
                    vehicle = vehicle,
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
