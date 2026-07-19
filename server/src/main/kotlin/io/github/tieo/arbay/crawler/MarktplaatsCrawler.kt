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
) : Crawler {

    override suspend fun search(query: SearchQuery): List<Listing> {
        val allResults = mutableListOf<Listing>()
        val seenIds = mutableSetOf<String>()
        val maxPages = CrawlerConfig.current.maxPages

        // Car queries are scoped to the auto-s (cars) category; everything else is a
        // free-text marketplace search. Pagination is a /p/N/ suffix on the base URL
        // (verified for both paths).
        val car = CarQueryResolver.resolve(query.positiveText)
        val text = if (car != null) {
            listOfNotNull(car.makeSlug.replace("-", " "), car.modelSlug).joinToString(" ")
        } else {
            query.positiveText
        }
        // Free-text search for everything. The old `/l/auto-s/q/` cars-category path returns 0 now
        // (the category-scoped URL rotted); the plain `/q/` search works, and for a car query the
        // make+model text is specific enough that relevance + the part guard keep it car-focused.
        val base = "$host/q/${text.encodeUrl()}/"

        for (page in 1..maxPages) {
            val url = if (page == 1) base else "${base}p/$page/"
            val html = try {
                CurlCffiClient.fetch(url, primeUrl = if (page == 1) host else null)
            } catch (e: CrawlerBlockedException) {
                if (page == 1) throw e
                break
            }

            val pageResults = parseSearchResults(html, requireVehicleSpecs = car != null)
            if (pageResults.isEmpty()) break

            val newResults = pageResults.filter { seenIds.add(it.externalId) }
            allResults.addAll(newResults)

            if (newResults.size < 10) break
            if (allResults.size >= CrawlerConfig.current.maxResultsPerPlatform) break
        }

        return allResults
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
            val price = Money.parse(priceText) ?: return@mapNotNull null

            // Drop lease/financing OFFERS structurally (not by price value). Marktplaats shows the
            // MONTHLY amount as the price (h5 "€ 373,-") with "Financial lease voor €373 p/mnd" in the
            // blurb. Two safe signals: (a) a "€<amt> p/mnd" whose amount equals the listed price — the
            // price IS the monthly figure; (b) an explicit "financial/private/operational lease" plus a
            // per-month marker. A real sale that merely mentions a lease option (price €10.950, blurb
            // "Leaseprijs: € 131") matches neither and stays.
            val cardText = item.text()
            val leaseAmt = Regex("""€\s?([\d.]{1,7})[,\-\s]*(?:p\s?/\s?mnd|p\s?/\s?m\b|per\s?maand)""", RegexOption.IGNORE_CASE)
                .find(cardText)?.groupValues?.get(1)?.replace(".", "")?.toLongOrNull()
            val priceIsMonthly = leaseAmt != null && leaseAmt == price.amount / 100
            val explicitLease = Regex("""(financial|private|operational|zakelijk)\s*lease""", RegexOption.IGNORE_CASE).containsMatchIn(cardText) &&
                Regex("""p\s?/\s?mnd|p\s?/\s?m\b|per\s?maand""", RegexOption.IGNORE_CASE).containsMatchIn(cardText)
            if (priceIsMonthly || explicitLease) return@mapNotNull null

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

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                description = descriptionText,
                shipping = shipping,
                vehicle = vehicle,
                scrapedAt = now,
            )
        }
    }
}
