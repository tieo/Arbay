package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.carCriteria
import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

class AutoScout24Crawler(
    private val client: HttpClient,
    private val countries: List<String> = EUROPE,
    override val platformId: PlatformId = PlatformId.AUTOSCOUT24,
) : Crawler {

    private val countryParam: String get() = countries.joinToString("%2C")

    override suspend fun search(query: SearchQuery): List<Listing> {
        val carQuery = CarQueryResolver.resolve(query.positiveText)
            ?: return searchByQueryParam(query)

        val basePath = buildString {
            append("https://www.autoscout24.de/lst/")
            append(carQuery.makeSlug)
            carQuery.modelSlug?.let { append("/").append(it) }
        }

        return paginate(query) { page ->
            val pageParam = if (page <= 1) "" else "&page=$page"
            val url = "$basePath?atype=C&cy=$countryParam&desc=0&sort=standard&ustate=N%2CU${filterParams(query)}$pageParam"
            val html = fetchWithFallback(client, url, "AutoScout24", waitSelector = "article")
            parseFromNextData(html) ?: parseFromHtml(html)
        }
    }

    /**
     * Fallback for queries that do not start with a known car make. The site ignores the
     * `query` parameter and serves its default feed; RelevanceFilter downstream detects
     * and discards such a result set, so a single page is enough.
     */
    private suspend fun searchByQueryParam(query: SearchQuery): List<Listing> {
        val url = "https://www.autoscout24.de/lst?atype=C&cy=$countryParam&desc=0&sort=standard&ustate=N%2CU${filterParams(query)}&query=${query.positiveText.encodeUrl()}"
        val html = fetchWithFallback(client, url, "AutoScout24", waitSelector = "article")
        return parseFromNextData(html) ?: parseFromHtml(html)
    }

    /** AutoScout24 supports every vehicle filter as a URL parameter, so the site returns
     *  only matching cars and far less needs scraping. Parameter names verified live. */
    private fun filterParams(query: SearchQuery): String = buildString {
        query.carCriteria.firstRegFromYear?.let { append("&fregfrom=$it") }
        query.carCriteria.firstRegToYear?.let { append("&fregto=$it") }
        query.carCriteria.maxMileageKm?.let { append("&kmto=$it") }
        query.maxPrice?.let { max ->
            val eur = if (max.currency == Currency.EUR) max.amount / 100
            else ExchangeRates.convert(max.amount, max.currency.name, "EUR") / 100
            append("&priceto=$eur")
        }
        query.minPrice?.let { min ->
            val eur = if (min.currency == Currency.EUR) min.amount / 100
            else ExchangeRates.convert(min.amount, min.currency.name, "EUR") / 100
            append("&pricefrom=$eur")
        }
        query.carCriteria.minPowerKw?.let { append("&powertype=kw&powerfrom=$it") }
        when (query.carCriteria.transmission) {
            Transmission.AUTOMATIC -> append("&gear=A")
            Transmission.MANUAL -> append("&gear=M")
            null -> {}
        }
    }

    companion object {
        /** AutoScout24 country codes reachable from the .de front end, covering Germany
         *  and the EU markets worth sourcing used vehicles from. Cross-border listings
         *  carry their origin in location.country. */
        // NOTE: "CH" is NOT a valid cy code here and zeroes the whole query — Switzerland needs its
        // real AutoScout24 country code (unknown; the .ch front end may use a different scheme).
        val EUROPE = listOf("D", "A", "B", "E", "F", "I", "L", "NL")
    }

    internal fun parseFromNextData(html: String): List<Listing>? {
        val doc = Jsoup.parse(html)
        val nextDataScript = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val now = Clock.System.now()

        return try {
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(nextDataScript).jsonObject
            val listings = root["props"]?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("listings")?.jsonArray
                ?: return null

            listings.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

                val vehicleTitle = buildString {
                    obj["vehicle"]?.jsonObject?.let { v ->
                        v["make"]?.jsonPrimitive?.contentOrNull?.let { append(it) }
                        v["model"]?.jsonPrimitive?.contentOrNull?.let { append(" $it") }
                        v["modelVersionInput"]?.jsonPrimitive?.contentOrNull?.let { append(" $it") }
                    }
                }.trim()

                val title = vehicleTitle.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                // Price is in tracking.price (raw integer) or price.priceFormatted
                val trackingPrice = obj["tracking"]?.jsonObject?.get("price")?.jsonPrimitive?.contentOrNull
                    ?.toLongOrNull()
                val price = if (trackingPrice != null) {
                    Money(trackingPrice * 100, Currency.EUR)
                } else {
                    val formatted = obj["price"]?.jsonObject?.get("priceFormatted")?.jsonPrimitive?.contentOrNull
                    formatted?.let { Money.parse(it) }
                } ?: return@mapNotNull null

                val relUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: "/angebote/$id"
                val url = "https://www.autoscout24.de$relUrl"

                // Images is a list of URL strings
                val imageUrl = obj["images"]?.jsonArray?.firstOrNull()?.let { el ->
                    when (el) {
                        is JsonPrimitive -> el.contentOrNull
                        is JsonObject -> el["url"]?.jsonPrimitive?.contentOrNull
                        else -> null
                    }
                }

                val locationObj = obj["location"]?.jsonObject
                val location = locationObj?.let {
                    Location(
                        city = it["city"]?.jsonPrimitive?.contentOrNull,
                        zip = it["zip"]?.jsonPrimitive?.contentOrNull,
                        country = it["countryCode"]?.jsonPrimitive?.contentOrNull,
                    )
                }

                val tracking = obj["tracking"]?.jsonObject
                val mileageText = tracking?.get("mileage")?.jsonPrimitive?.contentOrNull
                val yearText = tracking?.get("firstRegistration")?.jsonPrimitive?.contentOrNull

                val description = buildString {
                    yearText?.let { append("EZ: $it") }
                    mileageText?.let {
                        if (isNotEmpty()) append(" | ")
                        append("$it km")
                    }
                }.takeIf { it.isNotBlank() }

                // AutoScout24 carries structured vehicle attributes in the listing's `vehicle`
                // object (fuel, transmission, displacement) — authoritative, so verified.
                val v = obj["vehicle"]?.jsonObject
                val structured = VehicleInfo(
                    firstRegYear = yearText?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() },
                    mileageKm = mileageText?.replace(Regex("""[^0-9]"""), "")?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
                    fuel = Fuel.parse(v?.get("fuel")?.jsonPrimitive?.contentOrNull),
                    gearbox = Transmission.parse(v?.get("transmission")?.jsonPrimitive?.contentOrNull),
                    displacementCc = v?.get("engineDisplacementInCCM")?.jsonPrimitive?.contentOrNull
                        ?.replace(Regex("""[^0-9]"""), "")?.toIntOrNull()?.takeIf { it in 600..8000 },
                )
                val vehicle = VehicleTextParser.merge(
                    VehicleTextParser.verifiedByPresence(structured),
                    VehicleTextParser.parse("$title ${description ?: ""}"),
                )

                Listing(
                    id = "${platformId.name}:$id",
                    platformId = platformId,
                    externalId = id,
                    url = url,
                    title = title,
                    price = price,
                    imageUrls = listOfNotNull(imageUrl),
                    location = location,
                    description = description,
                    scrapedAt = now,
                    vehicle = vehicle,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseFromHtml(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        val items = doc.select("article[data-testid=list-item]")

        return items.mapNotNull { item ->
            val titleEl = item.selectFirst("h2 span") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isBlank()) return@mapNotNull null

            // Data attributes on the article element
            val mileage = item.attr("data-mileage").takeIf { it.isNotBlank() && it != "unknown" }
            val registration = item.attr("data-first-registration").takeIf { it.isNotBlank() && it != "new" }
            val zipCode = item.attr("data-listing-zip-code").takeIf { it.isNotBlank() }

            // Construct ID from href or use index
            val linkEl = item.selectFirst("a[href*='/angebote/']")
            val href = linkEl?.attr("href") ?: ""
            val externalId = Regex("""/angebote/([a-f0-9-]+)""").find(href)?.groupValues?.get(1)
                ?: item.hashCode().toString()
            val url = if (href.startsWith("http")) href else "https://www.autoscout24.de$href"

            val priceEl = item.selectFirst("[class*=price]")
            val priceText = priceEl?.text() ?: return@mapNotNull null
            val price = Money.parse(priceText) ?: return@mapNotNull null

            val imageUrl = item.selectFirst("picture source")?.attr("srcset")
                ?.split(",")?.firstOrNull()?.trim()?.split(" ")?.firstOrNull()

            val location = zipCode?.let { Location(zip = it) }

            val description = buildString {
                registration?.let { append("EZ: $it") }
                mileage?.let {
                    if (isNotEmpty()) append(" | ")
                    append("$it km")
                }
            }.takeIf { it.isNotBlank() }

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                location = location,
                description = description,
                scrapedAt = now,
            )
        }
    }
}
