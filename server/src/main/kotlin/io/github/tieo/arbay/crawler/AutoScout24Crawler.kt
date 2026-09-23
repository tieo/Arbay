package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.github.tieo.arbay.model.carCriteria
import io.ktor.client.*
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

class AutoScout24Crawler(
    private val client: HttpClient,
    private val countries: List<String> = EUROPE,
    override val platformId: PlatformId = PlatformId.AUTOSCOUT24,
) : Crawler, FetchesEveryPage, FiltersAtTheSource, KnowsLocation {
    override val nativeCriteria = setOf(
        FiltersAtTheSource.Criterion.YEAR, FiltersAtTheSource.Criterion.MILEAGE,
        FiltersAtTheSource.Criterion.PRICE, FiltersAtTheSource.Criterion.POWER,
        FiltersAtTheSource.Criterion.GEARBOX, FiltersAtTheSource.Criterion.FUEL,
        FiltersAtTheSource.Criterion.SELLER,
    )


    private val countryParam: String get() = countries.joinToString("%2C")

    /**
     * What the card never carries, off the ad's own page: the seller's own text, and the wheelbase
     * that is written inside it.
     *
     * This site has a `wheelBase` key in the page's own data and it was empty on every Crafter
     * measured. Where a wheelbase is stated at all it is a line of the dealer's equipment list —
     * "Radstand 3640 mm", "Radabstand: 4490 mm" — which is also the only place the full
     * description lives: the card's description is the site's own summary line ("EZ: 10-2020 |
     * 145737 km") and says nothing the specs do not.
     */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        val html = fetchWithFallback(client, listing.url, "AutoScout24", waitSelector = "main")
        val doc = Jsoup.parse(html)
        val description = sellersOwnText(doc)
        // Only the wheelbase is taken from the prose. Everything else on this page is already on
        // the card as a structured value, and reading it out of an advert's sales text again would
        // let "ab 199 € mtl., 1. Hand, 130.000 km Garantie" overwrite what the site itself stated.
        val wheelbase = VehicleTextParser.parseWheelbaseMm(description ?: doc.text())
        ListingDetail(
            vehicle = wheelbase?.let {
                VehicleInfo(wheelbaseMm = it, verified = setOf(VehicleField.WHEELBASE))
            },
            description = description,
        ).takeIf { it.vehicle != null || it.description != null }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        null
    }

    /**
     * The dealer's own description, out of the page's data island.
     *
     * The island carries several `description` keys — the site's own page blurb ("Finde jetzt
     * deinen Volkswagen …") among them — so the longest one wins, which is the advert every time.
     */
    private fun sellersOwnText(doc: org.jsoup.nodes.Document): String? {
        val island = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        val root = runCatching { Json.parseToJsonElement(island) }.getOrNull() ?: return null
        var longest: String? = null
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "description" && value is JsonPrimitive && value.isString) {
                        val text = value.content
                        if (text.length > (longest?.length ?: 0)) longest = text
                    }
                    walk(value)
                }
                is JsonArray -> element.forEach { walk(it) }
                else -> {}
            }
        }
        walk(root)
        // The advert is written as HTML inside that string; a reader wants the lines, not the tags.
        return longest
            ?.replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
            ?.replace(Regex("""</li>""", RegexOption.IGNORE_CASE), "\n")
            ?.replace(Regex("""<[^>]+>"""), " ")
            ?.let { org.jsoup.parser.Parser.unescapeEntities(it, false) }
            ?.replace(Regex("""[ \t]{2,}"""), " ")
            ?.replace(Regex("""\n{3,}"""), "\n\n")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    override suspend fun search(query: SearchQuery): List<Listing> {
        val carQuery = CarQueryResolver.resolveForCarSite(query.positiveText)
            ?: return emptyList()

        val basePath = buildString {
            append("https://www.autoscout24.de/lst/")
            append(carQuery.makeSlug)
            carQuery.modelSlug?.let { append("/").append(it) }
            // Four-wheel drive is an equipment slug here rather than a parameter — the site's own
            // "Volkswagen Crafter Allrad" link — and it narrows 1625 vans to 114 at the source.
            // Like every equipment list it is written by the sellers who bothered, so it is asked
            // for only when the search excludes what states nothing anyway.
            if (query.carCriteria.drivetrain == Drivetrain.AWD && query.carCriteria.strictUnknown)
                append("/eq_allrad")
        }

        return paginate(query) { page ->
            val pageParam = if (page <= 1) "" else "&page=$page"
            // A postcode radius is a statement about one country, and this site drops it when the
            // country list holds several. Centred on a place, the search asks that country.
            val cy = query.area(isoCountry(countries.firstOrNull()))
                ?.let { siteCountry(it.country) } ?: countryParam
            val url = "$basePath?atype=C&cy=$cy&desc=0&sort=standard&ustate=N%2CU${filterParams(query)}$pageParam"
            val html = fetchWithFallback(client, url, "AutoScout24", waitSelector = "article")
            parseFromNextData(html) ?: parseFromHtml(html)
        }
    }

    // A query that names no make this site knows used to be sent anyway, with the text in a
    // `query` parameter the site ignores: it answered with its default feed, and the relevance
    // filter threw the whole page away. Fetching a page in order to discard it is a request that
    // costs the same as a useful one and counts the same against this address, so the search is
    // simply not made. It was the single largest error class in the logs.

    /** The two-letter code as this site's own country letter. */
    private fun siteCountry(iso: String): String = when (iso.uppercase()) {
        "DE" -> "D"
        "AT" -> "A"
        "BE" -> "B"
        "ES" -> "E"
        "FR" -> "F"
        "IT" -> "I"
        "LU" -> "L"
        else -> iso.uppercase()
    }

    /** This site's own country letters as the two-letter codes everything else uses. */
    private fun isoCountry(siteCode: String?): String = when (siteCode?.uppercase()) {
        "D" -> "DE"
        "A" -> "AT"
        "B" -> "BE"
        "E" -> "ES"
        "F" -> "FR"
        "I" -> "IT"
        "L" -> "LU"
        else -> siteCode?.uppercase()?.takeIf { it.length == 2 } ?: "DE"
    }

    /** AutoScout24 supports every vehicle filter as a URL parameter, so the site returns
     *  only matching cars and far less needs scraping. Parameter names verified live. */
    private fun filterParams(query: SearchQuery): String = buildString {
        // Where the search is centred, as this site takes it: its own postcode field and a radius
        // in kilometres. Without it every search is nationwide and "near me" is only a label the
        // app draws afterwards.
        // The postcode has to belong to a country this instance covers, and this site writes those
        // in its own alphabet — "D" for Germany, "A" for Austria — which is not what a postcode
        // index is keyed by.
        query.area(isoCountry(countries.firstOrNull()))?.let { area ->
            area.zip?.let { append("&zip=$it&zipr=${area.radiusKm}") }
        }
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
        query.carCriteria.minMileageKm?.let { append("&kmfrom=$it") }
        query.carCriteria.minPowerKw?.let { append("&powertype=kw&powerfrom=$it") }
        query.carCriteria.maxPowerKw?.let { append("&powertype=kw&powerto=$it") }
        when (query.carCriteria.transmission) {
            Transmission.AUTOMATIC -> append("&gear=A")
            Transmission.MANUAL -> append("&gear=M")
            null -> {}
        }
        // Everything below is filtered by the site itself, each verified against its own result
        // count: asking for one fuel, one body, a door or seat count, an emission class or a kind
        // of seller narrows what comes back instead of narrowing it here afterwards. A crawl that
        // fetches what the criteria already rule out spends the page budget on listings that are
        // thrown away — a real search returned 74 vans and kept one.
        // Only where this site's own listings nearly all state the field, since its filter removes
        // every listing that states nothing and this app keeps those, marked unchecked. Measured by
        // asking for a range that excludes nothing, against 1625 Crafters: doors 1606, seats 1614,
        // fuel, mileage, power and seller all 1625 — but emission class only 1267 and body type
        // 446, so those two are filtered here rather than there.
        query.carCriteria.fuels.singleOrNull()?.let { fuel -> siteFuel(fuel)?.let { append("&fuel=$it") } }
        query.carCriteria.minDoors?.let { append("&doorfrom=$it") }
        query.carCriteria.minSeats?.let { append("&seatsfrom=$it") }
        query.carCriteria.sellerType?.let {
            append(if (it == SellerType.PRIVATE) "&custtype=P" else "&custtype=D")
        }
    }

    /** This site's own fuel letters. Measured on its result counts: D keeps the diesels, B the
     *  petrol ones, E the electric. */
    private fun siteFuel(fuel: Fuel): String? = when (fuel) {
        Fuel.DIESEL -> "D"
        Fuel.PETROL -> "B"
        Fuel.ELECTRIC -> "E"
        Fuel.LPG -> "L"
        Fuel.CNG -> "C"
        Fuel.HYBRID_PETROL, Fuel.HYBRID_DIESEL, Fuel.PLUGIN_HYBRID, Fuel.MILD_HYBRID -> "2"
        else -> null
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
