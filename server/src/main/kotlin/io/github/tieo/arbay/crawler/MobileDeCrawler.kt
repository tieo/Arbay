package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.slf4j.LoggerFactory

class MobileDeCrawler(private val client: HttpClient) : Crawler, FiltersAtTheSource, KnowsLocation, HasDetailSpecs, ReadsAdsTogether {
    override val nativeCriteria = setOf(
        FiltersAtTheSource.Criterion.YEAR, FiltersAtTheSource.Criterion.MILEAGE,
        FiltersAtTheSource.Criterion.PRICE, FiltersAtTheSource.Criterion.POWER,
        FiltersAtTheSource.Criterion.GEARBOX, FiltersAtTheSource.Criterion.FUEL,
        FiltersAtTheSource.Criterion.BODY, FiltersAtTheSource.Criterion.SELLER,
    )

    override val platformId = PlatformId.MOBILE_DE

    private val log = LoggerFactory.getLogger(MobileDeCrawler::class.java)

    override suspend fun search(query: SearchQuery): List<Listing> {
        // mobile.de is behind Akamai Bot Manager. Only real Google Chrome driven by zendriver
        // (undetected CDP) under a headed display passes it; StealthBrowserClient runs that.
        // Each category is a separate, slow stealth crawl, so only add the VanUpTo7500 category when
        // the query is actually after a large van (Crafter, Sprinter, …) — which does list there,
        // not under Car. A normal car search stays a single Car crawl (half the browser time).
        val resolved = CarQueryResolver.resolveForCarSite(query.positiveText)
        val categories = when {
            resolved == null -> listOf("Car")
            isVanQuery(resolved, query.toCarFilters()) -> listOf("Car", "VanUpTo7500")
            else -> listOf("Car")
        }
        // The model is chosen by this site's own numbers where it knows them, since its free-text
        // field searches the seller's description rather than the model: with criteria set and the
        // words in `q`, a Crafter search came back as thirty-four Tiguans that all met the
        // criteria. The words stay only as the fallback for a model the site does not list.
        val selection = resolved?.let {
            MobileDeCatalog.modelSelection(
                it.makeSlug.replace("-", " "),
                it.modelSlug?.replace("-", " "),
            )
        }
        val q = when {
            selection != null -> ""
            resolved != null -> listOfNotNull(resolved.makeSlug, resolved.modelSlug).joinToString(" ").encodeUrl()
            else -> query.positiveText.encodeUrl()
        }

        // Every page is a slow stealth browser navigation, so keep mobile.de deliberately shallow:
        // a few pages per category is already ~60-80 cars, and the result cap stops it early. A
        // deeper crawl here is what made a van search (two categories) take ~80 s.
        val cap = CrawlerConfig.current.maxResultsPerPlatform
        val maxPages = (query.maxPages ?: CrawlerConfig.current.maxPages).coerceAtMost(3)
        val platform = platformId.displayName
        val seen = mutableSetOf<String>()
        val all = mutableListOf<Listing>()

        for (vc in categories) {
            if (all.size >= cap) break // first category already filled the budget — skip the rest
            // mobile.de bypasses fetchWithFallback (dedicated stealth browser), so instrument and
            // enforce the request cutoff here too — it's the most block-sensitive platform.
            if (RequestMonitor.overBudget(platform))
                throw CrawlerBlockedException("$platform: request cutoff reached, skipping", ErrorType.RATE_LIMITED_429)
            val model = selection?.let { "&ms=${it.replace(";", "%3B")}%3B%3B" } ?: ""
            val url = "https://suchen.mobile.de/fahrzeuge/search.html?dam=0&isSearchRequest=true" +
                "&s=Car&sb=rel&vc=$vc$model&q=$q${filterParams(query)}"
            RequestMonitor.recordTier(platform, "Browser")
            var pageNo = 0
            val vcStart = System.currentTimeMillis()
            try {
                // Each page streams in as the stealth browser loads it; parse and surface it live
                // instead of blocking on the whole multi-page, multi-category crawl. When the sidecar
                // puts a captcha up for an interactive solve, forward that to the client.
                StealthBrowserClient.fetchStreaming(
                    url,
                    maxPages = maxPages,
                    onControl = { msg -> if (msg == "CAPTCHA_INTERACTIVE") emitCaptchaInteractive() },
                ) { html ->
                    pageNo++
                    RequestMonitor.recordRequest(platform)
                    val fresh = parseSearchResults(html).filter { seen.add(it.externalId) }
                    // Per-page trickle timing, so the stealth-browser cadence is visible in the log.
                    log.info("mobile.de {} page {} +{} (total {}, {}ms in)",
                        vc, pageNo, fresh.size, all.size + fresh.size, System.currentTimeMillis() - vcStart)
                    emitPartialResults(fresh)
                    all.addAll(fresh)
                }
            } catch (e: CrawlerBlockedException) {
                // A block with nothing collected yet is a real failure; otherwise keep what streamed
                // in — e.g. the Car category returned before the Van category got challenged.
                if (all.isEmpty()) throw e
            }
        }
        return all
    }

    override suspend fun fetchDetail(listing: Listing): ListingDetail? = fetchDetails(listOf(listing))[listing.id]

    /**
     * Each ad's own page, read in one stealth browser session: a session costs a browser start and
     * the bot check, an ad after that a page load.
     *
     * The card says "Volkswagen Crafter" and nothing about which one. The page has the seller's
     * headline, the trim line the dealer picked from the site's own catalogue ("35 lang Hochdach
     * FWD Trendline"), the equipment list, which is where the drive is ("Frontantrieb"), and the
     * seller's text, which is where a wheelbase is written ("Radstand 4490 mm").
     */
    override suspend fun fetchDetails(listings: List<Listing>): Map<String, ListingDetail> {
        if (listings.isEmpty()) return emptyMap()
        val platform = platformId.displayName
        if (RequestMonitor.overBudget(platform)) return emptyMap()
        val found = HashMap<String, ListingDetail>()
        var next = 0
        try {
            StealthBrowserClient.fetchAds(
                listings.map { "https://suchen.mobile.de/fahrzeuge/details.html?id=${it.externalId}" },
                onControl = { msg -> if (msg == "CAPTCHA_INTERACTIVE") emitCaptchaInteractive() },
            ) { html ->
                RequestMonitor.recordRequest(platform)
                val listing = listings.getOrNull(next++) ?: return@fetchAds
                parseAd(html)?.let { found[listing.id] = it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The ads read before the session failed stand; the rest stay as their cards say.
            log.warn("mobile.de ads: {} of {} read, then {}", next, listings.size, e.message)
        }
        log.info("mobile.de ads: {} of {} read", found.size, listings.size)
        return found
    }

    // The ad's own record in the page's streamed data: created, then modified and renewed, in
    // seconds. Renewed moves when a dealer pushes the ad up again; created is when it first went up.
    private val adCreated = Regex("""\\?"created\\?":(\d{9,11}),\\?"modified\\?":\d+,\\?"renewed\\?"""")

    // The dealer's rating in the page's streamed data: {"count":12,"totalCount":474,"score":4.9,…};
    // totalCount is the number the page shows ("474 Bewertungen").
    private val dealerReviews = Regex("""\\?"totalCount\\?":(\d+)""")

    private val euroClass = Regex("""euro\s*(\d)""", RegexOption.IGNORE_CASE)

    internal fun parseAd(html: String): ListingDetail? {
        val doc = Jsoup.parse(html)
        if (doc.selectFirst("[data-testid=vip-technical-data-box]") == null) return null
        // Each fact is a <dt data-testid="<name>-item"> followed by its <dd>.
        fun fact(name: String): String? =
            doc.selectFirst("dt[data-testid=$name-item]")?.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotBlank() }

        // The headline is the make and model the site sets plus a line the seller writes; the
        // element around both carries the whole of it as its label.
        val headline = doc.selectFirst("[data-testid=vip-ad-title]")?.parents()
            ?.firstOrNull { it.hasAttr("aria-label") }?.attr("aria-label")?.trim()?.takeIf { it.isNotBlank() }
        val sellersText = doc.selectFirst("[data-testid=vip-vehicle-description-text]")?.let { text ->
            text.select("br").forEach { it.after(TextNode("\n")) }
            text.wholeText().lines().joinToString("\n") { it.trim() }.trim().takeIf { it.isNotBlank() }
        }
        val trimLine = fact("trimLine")
        val features = doc.select("[data-testid=vip-features-list] li").map { it.text().trim() }.toSet()

        val drives = buildSet {
            if ("Frontantrieb" in features) add(Drivetrain.FWD)
            if ("Hinterradantrieb" in features) add(Drivetrain.RWD)
            if ("Allradantrieb" in features) add(Drivetrain.AWD)
        }
        val drivetrain = drives.singleOrNull()
        val wheelbase = sellersText?.let { VehicleTextParser.parseWheelbaseMm(it) }
        val seats = fact("numSeats")?.toIntOrNull()?.takeIf { it in 1..60 }
        val emission = fact("emissionClass")?.let { euroClass.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val vehicle = VehicleInfo(
            drivetrain = drivetrain,
            wheelbaseMm = wheelbase,
            seats = seats,
            emissionClassEuro = emission,
            verified = buildSet {
                if (drivetrain != null) add(VehicleField.DRIVETRAIN)
                if (wheelbase != null) add(VehicleField.WHEELBASE)
                if (seats != null) add(VehicleField.SEATS)
                if (emission != null) add(VehicleField.EMISSION)
            },
        )
        // What the size is read from: the headline, the trim line and the seller's text, in that
        // order, which is also how the page shows them.
        // The site's own air conditioning field ("Keine Klimaanlage oder -automatik") and the ticked
        // equipment, since a seller's text rarely says what a van lacks.
        val climate = fact("climatisation")?.let { "Klimatisierung: $it" }
        val equipment = features.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "Ausstattung: $it" }
        val description = listOfNotNull(headline, trimLine?.let { "Ausstattungslinie: $it" }, climate, equipment, sellersText)
            .joinToString("\n\n").takeIf { it.isNotBlank() }
        return ListingDetail(
            vehicle = vehicle.takeIf { it.verified.isNotEmpty() },
            description = description,
            listedAt = adCreated.find(html)?.groupValues?.get(1)?.toLongOrNull()
                ?.let { kotlin.time.Instant.fromEpochSeconds(it) },
            sellerReviews = dealerReviews.find(html)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 },
        ).takeIf { it.vehicle != null || it.description != null }
    }

    /** mobile.de's native URL filter params, so the site returns only matching cars instead of
     *  everything (which then leaks unverifiable cards past the post-filter). Range params take
     *  `min:max`; either side may be empty. Names verified live against the search page: fr =
     *  first-registration year, ml = mileage, pw = power in kW, p = price in EUR, tr = gearbox. */
    private fun filterParams(query: SearchQuery): String {
        // The area is not a car criterion, so it is built even for a search that sets none.
        val area = query.area("DE")?.let { "&ll=${it.latitude}%2C${it.longitude}&rad=${it.radiusKm}" } ?: ""
        val f = query.toCarFilters() ?: return area
        fun range(min: Any?, max: Any?): String? =
            if (min != null || max != null) "${min ?: ""}:${max ?: ""}" else null
        return buildString {
            append(area)
            range(f.firstRegFromYear, f.firstRegToYear)?.let { append("&fr=$it") }
            range(f.minMileageKm, f.maxMileageKm)?.let { append("&ml=$it") }
            range(f.minPowerKw, f.maxPowerKw)?.let { append("&pw=$it") }
            range(f.minPriceEur, f.maxPriceEur)?.let { append("&p=$it") }
            when (f.transmission) {
                Transmission.AUTOMATIC -> append("&tr=AUTOMATIC_GEAR")
                Transmission.MANUAL -> append("&tr=MANUAL_GEAR")
                null -> {}
            }
            // Names and values taken from this site's own filter definition, which it ships inside
            // the search page: `dt` Antriebsart, `ft` Kraftstoffart, `c` Fahrzeugtyp, `door`,
            // `sc` Sitzplätze, `emc` Schadstoffklasse, `st` Anbieter. Everything the site can
            // narrow itself is narrowed there, so the pages fetched are pages of candidates.
            f.fuels.forEach { fuel -> siteFuel(fuel)?.let { append("&ft=$it") } }
            f.bodyTypes.mapNotNull { siteBody(it) }.distinct().forEach { append("&c=$it") }
            f.sellerType?.let { append(if (it == SellerType.PRIVATE) "&st=FSBO" else "&st=DEALER") }
            // A site's filter removes what states nothing, which this app keeps and marks
            // unchecked — so the fields most of this site's own ads leave empty are only asked for
            // when the search itself excludes unknowns. Measured over 2875 Crafters: a drive type
            // on 2221 of them (77%), a door count on 258 (9%), a seat count on 2444 (85%), an
            // emission class on 2158 (75%). Fuel and vehicle type are on effectively all of them.
            if (f.strictUnknown) {
                when (f.drivetrain) {
                    Drivetrain.AWD -> append("&dt=ALL_WHEEL")
                    Drivetrain.FWD -> append("&dt=FRONT")
                    Drivetrain.RWD -> append("&dt=REAR")
                    null -> {}
                }
                f.minDoors?.let { doors ->
                    append("&door=" + if (doors >= 6) "SIX_OR_SEVEN" else if (doors >= 4) "FOUR_OR_FIVE" else "TWO_OR_THREE")
                }
                f.minSeats?.let { append("&sc=$it:") }
                f.minEmissionEuro?.let { if (it in 1..7) append("&emc=EURO$it") }
            }
        }
    }

    /** This site's own fuel names. */
    private fun siteFuel(fuel: Fuel): String? = when (fuel) {
        Fuel.PETROL -> "PETROL"
        Fuel.DIESEL -> "DIESEL"
        Fuel.ELECTRIC -> "ELECTRICITY"
        Fuel.HYBRID_PETROL, Fuel.PLUGIN_HYBRID, Fuel.MILD_HYBRID -> "HYBRID"
        Fuel.HYBRID_DIESEL -> "HYBRID_DIESEL"
        Fuel.LPG -> "LPG"
        Fuel.CNG -> "CNG"
        Fuel.HYDROGEN -> "HYDROGENIUM"
        Fuel.ETHANOL -> "ETHANOL"
        Fuel.OTHER -> null
    }

    /** This site's own vehicle types. */
    private fun siteBody(body: BodyType): String? = when (body) {
        BodyType.SMALL_CAR -> "SmallCar"
        BodyType.SEDAN -> "Limousine"
        BodyType.ESTATE -> "EstateCar"
        BodyType.SUV, BodyType.PICKUP -> "OffRoad"
        BodyType.COUPE -> "SportsCar"
        BodyType.CONVERTIBLE -> "Cabrio"
        BodyType.VAN, BodyType.MINIVAN, BodyType.TRANSPORTER -> "Van"
        BodyType.OTHER -> "OtherCar"
    }

    /** Whether this query is after a large van/transporter, which mobile.de lists under the separate
     *  VanUpTo7500 category. True when a van body-type filter is set or the model is a known
     *  transporter; a normal car then skips that second, expensive stealth crawl. */
    private fun isVanQuery(car: CarQueryResolver.CarQuery, filters: CarFilters?): Boolean {
        if (filters?.bodyTypes?.any { it == BodyType.VAN || it == BodyType.MINIVAN || it == BodyType.TRANSPORTER } == true)
            return true
        val model = car.modelSlug?.lowercase() ?: return false
        return VAN_MODELS.any { model == it || model.startsWith("$it-") }
    }

    private val VAN_MODELS = setOf(
        "crafter", "sprinter", "transporter", "transit", "ducato", "boxer", "jumper", "master",
        "movano", "interstar", "daily", "vito", "viano", "trafic", "vivaro", "talento", "primastar",
        "expert", "jumpy", "scudo", "proace", "combo", "doblo", "nv200", "nv300", "nv400", "hiace",
    )

    // Result cards render with CSS-module hashed classes and stable data-testid values: each
    // listing container is `(top|base)-result-listing-N`, holding a `listing-title-card-view`
    // title, a `main-price-label` price and a `/fahrzeuge/details` link.
    private val containerTestId = Regex("^(?:top|base)-result-listing-\\d+$")
    private val idInHref = Regex("""[?&]id=(\d+)""")
    private val firstPrice = Regex("""([0-9][0-9.]*)\s*€""")
    private val regInfo = Regex("""EZ\s*(\d{1,2}/\d{4})""")
    private val kmInfo = Regex("""([\d.]+)\s*km""")
    private val kwInfo = Regex("""(\d+)\s*kW""")

    /** Where the seller is, as mobile.de writes it on the card: "DE-29227 Celle", "29227 Celle".
     *  Every listing had only "DE" on it, so no mobile.de result could say how far away it was. */
    private val placeInfo = Regex("""\b(?:DE-)?(\d{5})\s+([A-ZÄÖÜ][\p{L}.\-]+(?:\s[A-ZÄÖÜ][\p{L}.\-]+){0,2})""")

    /** The place line of a card's seller box, whole: "21244 Buchholz in der Nordheide". */
    private val sellerBoxPlace = Regex("""(?:DE-)?(\d{5})\s+(\S.*)""")

    internal fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        val items = doc.select("[data-testid]").filter { containerTestId.matches(it.attr("data-testid")) }
        val sellers = sellersByListing(doc)

        return items.mapNotNull { item ->
            val href = item.selectFirst("a[href*=/fahrzeuge/details]")?.attr("href")
                ?: return@mapNotNull null
            val externalId = idInHref.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
            val url = if (href.startsWith("http")) href else "https://suchen.mobile.de$href"

            // `listing-title-card-view` is the bare model name; the `-title` wrapper also holds a
            // "Gesponsert" sponsored badge and a subtitle, which would leak into the title text.
            val title = (item.selectFirst("[data-testid=listing-title-card-view]")
                ?: item.selectFirst("[data-testid$=-title]"))?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            // Price lives in `main-price-label` ("42.900 €"); `price-label` is the same amount
            // elsewhere in the card, `-price-section` is the legacy testid. nbsp (U+00A0) between
            // the number and € would defeat the \s* in firstPrice, so normalise it to a space.
            val priceText = (item.selectFirst("[data-testid=main-price-label]")
                ?: item.selectFirst("[data-testid=price-label]")
                ?: item.selectFirst("[data-testid$=-price-section]")
                ?: item.selectFirst("[class*=Price],[class*=price]"))?.text()?.replace('\u00a0', ' ')
            val price = priceText?.let { firstPrice.find(it)?.value }?.let { Money.parse(it) }
                ?: return@mapNotNull null

            val info = item.text().replace('\u00a0', ' ')
            val reg = regInfo.find(info)?.groupValues?.get(1)  // "MM/YYYY"
            val description = buildString {
                reg?.let { append("EZ: $it") }
                kmInfo.find(info)?.let { if (isNotEmpty()) append(" | "); append("${it.groupValues[1]} km") }
                kwInfo.find(info)?.let { if (isNotEmpty()) append(" | "); append("${it.groupValues[1]} kW") }
            }.takeIf { it.isNotBlank() }

            // The card text carries EZ/km/kW precisely; fuel, gearbox and body come from the
            // rest of the same text via the shared parser.
            val vehicle = VehicleTextParser.merge(
                VehicleTextParser.verifiedByPresence(VehicleInfo(
                    firstRegYear = reg?.substringAfter("/")?.toIntOrNull(),
                    firstRegMonth = reg?.substringBefore("/")?.toIntOrNull(),
                    mileageKm = kmInfo.find(info)?.groupValues?.get(1)?.replace(".", "")?.toIntOrNull()
                        ?.takeIf { it in 1..2_000_000 },
                    powerKw = kwInfo.find(info)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 20..1000 },
                )),
                VehicleTextParser.parse(info),
            )

            // The seller box holds one line each, in their own elements: the dealer's name or
            // "Privatanbieter", then the place, then a rating. Read as one text they run together
            // ("Privatanbieter91056 Erlangen"), so the lines are taken one by one.
            val boxLines = item.selectFirst("[data-testid=seller-info]")?.select("span")
                ?.filter { it.children().isEmpty() }?.map { it.text().trim() }?.filter { it.isNotEmpty() }
                .orEmpty()
            val placeAt = boxLines.indexOfFirst { sellerBoxPlace.matches(it) }
            val boxPlace = boxLines.getOrNull(placeAt)?.let { line -> line to sellerBoxPlace.matchEntire(line)!! }
            // The page's data names the dealer's account but no longer its name; the box does.
            val seller = sellers[externalId]?.let { known ->
                val boxName = boxLines.getOrNull(placeAt - 1)?.takeIf { placeAt > 0 }
                if (known.type == SellerType.BUSINESS && known.name == null && boxName != null) known.copy(name = boxName)
                else known
            }

            val imageUrl = item.selectFirst("img")?.let {
                it.attr("src").ifBlank { it.attr("data-src") }.ifBlank { it.attr("srcset").substringBefore(" ") }
            }?.takeIf { it.startsWith("http") }

            Listing(
                id = "${platformId.name}:$externalId",
                platformId = platformId,
                externalId = externalId,
                url = url,
                title = title,
                price = price,
                imageUrls = listOfNotNull(imageUrl),
                location = boxPlace?.let { (line, m) ->
                    Location(city = m.groupValues[2].trim(), zip = m.groupValues[1], country = "DE", raw = line)
                } ?: placeInfo.find(info)?.let { m ->
                    Location(city = m.groupValues[2].trim(), zip = m.groupValues[1], country = "DE", raw = m.value)
                } ?: Location(country = "DE"),
                description = description,
                scrapedAt = now,
                vehicle = vehicle,
                seller = seller,
            )
        }
    }

    /**
     * Who sells each listing on the page, by the listing's number.
     *
     * The card itself shows only the dealer's name. The page also carries every listing as data, in
     * the chunks its framework streams in (`self.__next_f.push([1,"…"])`), and there each one names
     * its seller by the site's own account number (`sellerId`) next to a `contact` with the
     * dealer's name and whether it is a dealer at all. Measured on a Crafter search: every one of
     * the 24 cards on the page had its entry.
     *
     * A private seller's contact name is a person's, and is not kept; the number is enough.
     */
    internal fun sellersByListing(doc: org.jsoup.nodes.Document): Map<String, Seller> {
        val payload = buildString {
            doc.select("script").forEach { script ->
                val data = script.data().trim()
                if (!data.startsWith(FLIGHT_PUSH) || !data.endsWith("])")) return@forEach
                val literal = data.substring(FLIGHT_PUSH.length, data.length - 2)
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(literal) }.getOrNull()
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.takeIf { it.isString }?.let { append(it.content) }
            }
        }
        val found = HashMap<String, Seller>()
        fun walk(element: kotlinx.serialization.json.JsonElement) {
            when (element) {
                is kotlinx.serialization.json.JsonObject -> {
                    val listingId = (element["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val sellerId = (element["sellerId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (listingId != null && sellerId != null && listingId !in found) {
                        val contact = element["contact"] as? kotlinx.serialization.json.JsonObject
                        fun text(key: String) = (contact?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
                            ?.content?.trim()?.takeIf { it.isNotBlank() }
                        val dealer = text("enumType") == "DEALER"
                        val rating = contact?.get("rating") as? kotlinx.serialization.json.JsonObject
                        fun ratingValue(key: String) = (rating?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
                        val ratingPage = ratingValue("link")
                        // The dealer's stars on this site, out of 5. How many reviews they rest on is
                        // `totalCount`, which only the ad page carries; the card's `count` is a part
                        // of them (12 for a dealer the page shows with 474) and is not taken.
                        val stars = ratingValue("score")?.toDoubleOrNull()
                            ?.takeIf { (ratingValue("count")?.toIntOrNull() ?: 0) > 0 }
                        val reviews = ratingValue("totalCount")?.toIntOrNull()?.takeIf { it > 0 }
                        found[listingId] = Seller(
                            id = sellerId,
                            type = if (dealer) SellerType.BUSINESS else SellerType.PRIVATE,
                            name = if (dealer) text("name") else null,
                            url = if (dealer) ratingPage?.takeIf { it.startsWith("https://") }?.substringBefore('?') else null,
                            rating = if (dealer) stars else null,
                            reviewCount = if (dealer) reviews else null,
                        )
                    }
                    element.values.forEach(::walk)
                }
                is kotlinx.serialization.json.JsonArray -> element.forEach(::walk)
                else -> {}
            }
        }
        // One row per line, "<id>:<value>"; the rows that are JSON hold the data, the rest
        // (module references, text) are skipped.
        payload.lineSequence().forEach { line ->
            val value = line.substringAfter(':', "")
            if (value.isEmpty() || value[0] !in "[{") return@forEach
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(value) }.getOrNull()?.let(::walk)
        }
        return found
    }

    private companion object {
        const val FLIGHT_PUSH = "self.__next_f.push([1,"
    }
}
