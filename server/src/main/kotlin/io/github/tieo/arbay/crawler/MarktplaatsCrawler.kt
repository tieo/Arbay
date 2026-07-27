package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
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
    override suspend fun fetchDetailVehicle(listing: Listing): VehicleInfo? = try {
        MarktplaatsDetailParser.parse(CurlCffiClient.fetch(listing.url, primeUrl = host))
    } catch (_: Exception) {
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
        val base = "$host/q/${text.encodeUrl()}/"

        return paginate(query) { page ->
            val url = if (page <= 1) base else "${base}p/$page/"
            val html = CurlCffiClient.fetch(url, primeUrl = if (page <= 1) host else null)
            parseSearchResults(html, requireVehicleSpecs = car != null)
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
