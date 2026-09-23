package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jsoup.Jsoup

/**
 * Crawler for the Adevinta classifieds stack shared by marktplaats.nl (NL) and 2dehands.be (BE):
 * same /q/ search path, same hz-Listing markup, same hz-attributes spec row. Only the host and
 * the country the listings sit in differ.
 */
class MarktplaatsCrawler(
    private val client: HttpClient,
    override val platformId: PlatformId = PlatformId.MARKTPLAATS,
    private val host: String = "https://www.marktplaats.nl",
) : Crawler, FetchesEveryPage, KnowsLocation, HasDetailSpecs {

    /** The search card carries only year and mileage; the detail page's attribute object adds
     *  power, gearbox, fuel, doors, body type and colour, so a filter on those verifies instead of
     *  soft-passing. Fetched only for the listings DetailEnricher selects (budgeted + cached). */
    override suspend fun fetchDetail(listing: Listing): ListingDetail? = try {
        MarktplaatsDetailParser.parse(CurlCffiClient.fetch(listing.url, primeUrl = host))
            ?.let { ListingDetail(vehicle = it) }
    } catch (e: CancellationException) { throw e } catch (_: Exception) {
        null
    }

    override suspend fun search(query: SearchQuery): List<Listing> {
        // Car queries carry their make+model as the free-text term; everything else searches the
        // marketplace verbatim. Pagination is a /p/N/ suffix on the base URL. The old `/l/auto-s/q/`
        // cars-category path returns 0 now (the category-scoped URL rotted); the plain `/q/` search
        // works, and for a car query the make+model text plus relevance + the part guard keep it
        // car-focused.
        val car = CarQueryResolver.resolve(query.positiveText)
        val text = if (car != null) {
            listOfNotNull(car.makeSlug.replace("-", " "), car.modelSlug).joinToString(" ")
        } else {
            query.positiveText
        }
        // A car search goes to the cars category with the criteria this site filters itself,
        // rather than to the whole marketplace: "volkswagen crafter" site-wide is 6851 results,
        // almost all of them parts, and 1059 inside the category. Verified on this site's own
        // counts: the gearbox is stated by 1040 of those 1059 and the fuel by 1047, so both are
        // asked for; a drive type by 796, so that one waits for a search that excludes unstated
        // specs. The filters are path segments, `/f/<name>/<id>/`, taken from its own facet links.
        val filterPath = if (car == null || !host.contains("marktplaats")) "" else buildString {
            val criteria = query.carCriteria
            when (criteria.transmission) {
                Transmission.AUTOMATIC -> append("f/automaat/534/")
                Transmission.MANUAL -> append("f/handgeschakeld/535/")
                null -> {}
            }
            criteria.fuels.singleOrNull()?.let { fuel ->
                when (fuel) {
                    Fuel.DIESEL -> append("f/diesel/474/")
                    Fuel.ELECTRIC -> append("f/elektrisch/11756/")
                    else -> {}
                }
            }
            if (criteria.strictUnknown) when (criteria.drivetrain) {
                Drivetrain.AWD -> append("f/vierwielaandrijving/13945/")
                Drivetrain.FWD -> append("f/voorwielaandrijving/13943/")
                Drivetrain.RWD -> append("f/achterwielaandrijving/13944/")
                null -> {}
            }
        }
        val base = if (car != null && host.contains("marktplaats"))
            "$host/l/auto-s/$filterPath" + "q/${text.encodeUrl()}/"
        else "$host/q/${text.encodeUrl()}/"

        val carsCategory = car != null && host.contains("marktplaats")
        return paginate(query) { page ->
            val url = if (page <= 1) base else "${base}p/$page/"
            val html = CurlCffiClient.fetch(url, primeUrl = if (page <= 1) host else null)
            if (carsCategory) parseCarsCategory(html)
            else parseSearchResults(html, requireVehicleSpecs = car != null)
        }
    }

    private companion object {
        /** A monthly instalment: "€373 p/mnd", "€597 p/m", or a bare "Leaseprijs: € 131". */
        val LEASE_PRICE = Regex(
            """(?:€\s?([\d.]{1,7})(?:,\d{2})?[,\-\s]*(?:p\s?/\s?mnd|p\s?/\s?m\b|per\s?maand)""" +
                """|lease\s?prijs\s*:?\s*€\s?([\d.]{1,7}))""",
            RegexOption.IGNORE_CASE,
        )

        /** An advert whose title offers a lease rather than a sale, so its price is an instalment
         *  and the vehicle is not actually for sale at that figure. */
        val LEASE_OFFER_TITLE = Regex(
            """^\s*(zakelijke|financial|private|operational)\s*lease\b|^\s*lease\s*(vanaf|deal)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** What the van actually sells for, where a lease advert also states it. */
        val PURCHASE_PRICE = Regex(
            """(?:koop\s?(?:direct)?\s?voor|koopprijs|vraagprijs)\s*:?\s*€\s?([\d.]{2,9})""",
            RegexOption.IGNORE_CASE,
        )

        /** Dutch amounts group thousands with a dot: "36.250" is 36250 euro. */
        fun String.euroAmount(): Long? = replace(".", "").toLongOrNull()
    }

    /**
     * @param requireVehicleSpecs keep only ads that state a year or an odometer reading in the
     *  attribute row. On a car query this separates vehicles from the parts trade that dominates
     *  these classifieds: a vehicle ad always carries the row, a part ("Roetfilter", "ABS Pomp",
     *  "Expansievat van een Crafter") never does.
     */
    /**
     * The cars category's own listing data, out of the page's data island.
     *
     * That page draws a different card from the marketplace-wide search — the HTML parser below
     * finds nothing in it — and it carries far more than the card ever did: the site's own
     * `constructionYear`, `mileage`, `fuel` and `transmission` attributes, which are stated values
     * rather than numbers read out of a title, plus the seller's coordinates.
     */
    internal fun parseCarsCategory(html: String): List<Listing> {
        val island = Jsoup.parse(html).selectFirst("script#__NEXT_DATA__")?.data() ?: return emptyList()
        val root = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(island) }
            .getOrNull() ?: return emptyList()
        val listings = mutableListOf<JsonObject>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "listings" && value is JsonArray) {
                        value.forEach { entry ->
                            (entry as? JsonObject)?.takeIf { it["itemId"] != null }?.let { listings += it }
                        }
                    }
                    walk(value)
                }
                is JsonArray -> element.forEach { walk(it) }
                else -> {}
            }
        }
        walk(root)
        val now = Clock.System.now()
        return listings.distinctBy { it["itemId"]?.jsonPrimitive?.content }.mapNotNull { item ->
            val id = item["itemId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = item["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val cents = item["priceInfo"]?.jsonObject?.get("priceCents")?.jsonPrimitive?.longOrNull
                ?: return@mapNotNull null
            if (cents <= 0L) return@mapNotNull null
            val path = item["vipUrl"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val attributes = item["attributes"]?.jsonArray.orEmpty().mapNotNull { entry ->
                val obj = entry as? JsonObject ?: return@mapNotNull null
                val key = obj["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                key to value
            }.toMap()
            val vehicle = VehicleInfo(
                firstRegYear = attributes["constructionYear"]?.toIntOrNull()?.takeIf { it in 1950..2035 },
                mileageKm = attributes["mileage"]?.replace(".", "")?.toIntOrNull()?.takeIf { it in 1..2_000_000 },
                fuel = Fuel.parse(attributes["fuel"]),
                gearbox = when {
                    attributes["transmission"]?.contains("utomaat", true) == true -> Transmission.AUTOMATIC
                    attributes["transmission"]?.contains("andgeschakeld", true) == true -> Transmission.MANUAL
                    else -> null
                },
            ).let { VehicleTextParser.verifiedByPresence(it) }
            val place = item["location"]?.jsonObject
            Listing(
                id = "${platformId.name}:$id",
                platformId = platformId,
                externalId = id,
                url = if (path.startsWith("http")) path else "$host$path",
                title = title,
                description = item["description"]?.jsonPrimitive?.contentOrNull,
                price = Money(cents, Currency.EUR),
                imageUrls = item["imageUrls"]?.jsonArray.orEmpty().mapNotNull { image ->
                    image.jsonPrimitive.contentOrNull?.let { if (it.startsWith("//")) "https:$it" else it }
                },
                location = place?.let { where ->
                    Location(
                        city = where["cityName"]?.jsonPrimitive?.contentOrNull,
                        country = where["countryAbbreviation"]?.jsonPrimitive?.contentOrNull,
                        latitude = where["latitude"]?.jsonPrimitive?.doubleOrNull,
                        longitude = where["longitude"]?.jsonPrimitive?.doubleOrNull,
                    )
                },
                seller = item["sellerInformation"]?.jsonObject?.get("sellerName")?.jsonPrimitive?.contentOrNull
                    ?.let { Seller(name = it) },
                vehicle = vehicle.takeIf { it != VehicleInfo() },
                scrapedAt = now,
            )
        }
    }

    private fun parseSearchResults(html: String, requireVehicleSpecs: Boolean = false): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        // Marktplaats uses li containers with class hz-Listing--list-item (or hz-Listing--list-item-new).
        // Class names can vary by query / A-B test, so use attribute-contains selector for robustness.
        val items = doc.select("li[class*=hz-Listing--list-item]")
            .ifEmpty { doc.select("div[class*=hz-Listing--list-item]") }

        return items.mapNotNull { item ->
            // Link: find the first <a> with an href to /v/ (actual listing page)
            val linkEl = item.selectFirst("a[href^='/v/']") ?: return@mapNotNull null
            val href = linkEl.attr("href")
            val url = "$host$href"
            val externalId = Regex("""[/-]a?(\d{7,})""").find(href)?.groupValues?.get(1)
                ?: Regex("""/m(\d+)""").find(href)?.groupValues?.get(1)
                ?: return@mapNotNull null

            // Title: Marktplaats uses CSS Modules so class names get hashed suffixes like
            // "ListingTitle_hz-Listing-title-new__YIv8B". Use attribute substring match.
            val titleEl = item.selectFirst("h3.hz-Listing-title")
                ?: item.selectFirst("[class*=hz-Listing-title]")
                ?: return@mapNotNull null
            // The title span is often nested inside the div — get the deepest span text.
            val title = (titleEl.selectFirst("span") ?: titleEl).text().trim()
            if (title.isBlank()) return@mapNotNull null

            // Price: also uses CSS Modules hashed names. The price element may be an h5.
            val priceEl = item.selectFirst("[class*=hz-Listing-price--desktop]")
                ?: item.selectFirst("[class*=hz-Listing-price]")
                ?: return@mapNotNull null
            // Unwrap nested h5/span if present
            val priceText = (priceEl.selectFirst("h5, span") ?: priceEl).text().trim()
            val cardPrice = Money.parse(priceText) ?: return@mapNotNull null

            // A lease advert puts the MONTHLY instalment where a sale puts the asking price, in
            // several shapes: "Financial lease voor €373 p/mnd", "... €597 p/m of koop direct voor
            // €36.250,-", and a bare "Leaseprijs: € 131" with no per-month marker at all. Showing
            // any of them as the price advertises a van at a fraction of what it costs.
            //
            // Where the advert also states what the van actually sells for, that price is used;
            // otherwise the listing is dropped, because its price cannot be trusted. This is a
            // structural read of the advert, never a threshold on the amount: a genuinely cheap or
            // broken van has no lease wording and stays.
            val cardText = item.text()
            val leaseMatch = LEASE_PRICE.find(cardText)
            val leasePrice = leaseMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.euroAmount()
            val priceIsInstalment = leasePrice != null && leasePrice == cardPrice.amount / 100
            val purchasePrice = PURCHASE_PRICE.find(cardText)?.groupValues?.get(1)?.euroAmount()

            val isLeaseOffer = priceIsInstalment || LEASE_OFFER_TITLE.containsMatchIn(title)
            val price = when {
                // A stated purchase price above the instalment is what the van actually costs.
                isLeaseOffer && purchasePrice != null && purchasePrice > cardPrice.amount / 100 ->
                    Money(purchasePrice * 100, Currency.EUR)
                isLeaseOffer -> return@mapNotNull null
                else -> cardPrice
            }

            // Structured spec row (Bouwjaar / conditie / kilometerstand), rendered as hz-attributes
            // and distinct from the marketing blurb. Parsing it yields verified mileage + year, so the
            // km/year filters act on real values instead of APK expiry dates or lease amounts that
            // pollute the free-text description (a 232.583 km van was leaking a ≤200k filter because
            // mileage was never read off the card).
            val attrText = item.selectFirst("[class*=hz-attributes]")?.text()
            val vehicle = attrText?.let { at ->
                val km = Regex("""([0-9][0-9.]{2,})\s*km""", RegexOption.IGNORE_CASE)
                    .find(at)?.groupValues?.get(1)?.replace(".", "")?.toIntOrNull()?.takeIf { it in 1..2_000_000 }
                val year = Regex("""\b(19[89]\d|20[0-3]\d)\b""").find(at)?.value?.toIntOrNull()
                val condition = VehicleCondition.parse(at)
                if (km == null && year == null && condition == null) null
                else VehicleTextParser.verifiedByPresence(
                    VehicleInfo(mileageKm = km, firstRegYear = year, condition = condition),
                )
            }

            if (requireVehicleSpecs && vehicle?.let { it.mileageKm != null || it.firstRegYear != null } != true) {
                return@mapNotNull null
            }

            val descriptionText = item.selectFirst("[class*=hz-Listing-description]")?.text()

            val imageUrl = item.selectFirst("[class*=hz-Listing-image-container] img, [class*=hz-Listing-image] img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }
            }?.takeIf { it.startsWith("http") }

            // Shipping: Marktplaats shows "Verzending" (shipping) / "Ophalen" (pickup)
            val shippingEl = item.select("span").firstOrNull { it.text().let { t ->
                t.contains("Verzending", true) || t.contains("Ophalen", true) || t.contains("shipping", true)
            } }?.text()
            val shipping = when {
                shippingEl == null -> null
                shippingEl.contains("Ophalen", true) -> Shipping(pickup = true)
                else -> {
                    val cost = Money.parse(shippingEl)
                    if (cost != null) Shipping(cost = cost) else null
                }
            }

            // Seller town (hashed class, so match the stable "sellerLocation" fragment), so the card
            // shows where the item is, not just on a distance sort.
            val locationText = item.selectFirst("[class*=sellerLocation], [class*=location]")?.text()?.trim()
            val location = locationText?.takeIf { it.isNotBlank() }?.let { Location.parse(it) }

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                description = descriptionText,
                location = location,
                shipping = shipping,
                vehicle = vehicle,
                scrapedAt = now,
            )
        }
    }
}
