package io.github.tieo.arbay.crawler

import io.github.tieo.arbay.model.*
import io.ktor.client.*
import kotlinx.datetime.Clock
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory

class MobileDeCrawler(private val client: HttpClient) : Crawler, FiltersAtTheSource, KnowsLocation {
    override val nativeCriteria = setOf(FiltersAtTheSource.Criterion.YEAR, FiltersAtTheSource.Criterion.MILEAGE, FiltersAtTheSource.Criterion.PRICE, FiltersAtTheSource.Criterion.POWER, FiltersAtTheSource.Criterion.GEARBOX)

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
        val q = if (resolved != null) {
            listOfNotNull(resolved.makeSlug, resolved.modelSlug).joinToString(" ").encodeUrl()
        } else {
            query.positiveText.encodeUrl()
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
            val url = "https://suchen.mobile.de/fahrzeuge/search.html?dam=0&isSearchRequest=true&s=Car&sb=rel&vc=$vc&q=$q${filterParams(query)}"
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
        }
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

    internal fun parseSearchResults(html: String): List<Listing> {
        val doc = Jsoup.parse(html)
        val now = Clock.System.now()

        val items = doc.select("[data-testid]").filter { containerTestId.matches(it.attr("data-testid")) }

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
                location = placeInfo.find(info)?.let { m ->
                    Location(city = m.groupValues[2].trim(), zip = m.groupValues[1], country = "DE", raw = m.value)
                } ?: Location(country = "DE"),
                description = description,
                scrapedAt = now,
                vehicle = vehicle,
            )
        }
    }
}
