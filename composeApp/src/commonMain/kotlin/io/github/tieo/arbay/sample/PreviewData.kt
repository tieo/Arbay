package io.github.tieo.arbay.sample

import io.github.tieo.arbay.model.*
import kotlinx.datetime.Instant

/** Realistic sample listings for the gallery — a "parkettschleifmaschine" search: real machines,
 *  mixed platforms/countries, new and used, plus a few sold ones with dates for the history chart. */
/**
 * The screens' sample data, in one place.
 *
 * Both ways of drawing a screen without a server use this: the off-screen
 * renderer that writes the model's pictures, and the @Preview functions the
 * Android screenshot toolchain renders. Two copies would be two answers to what
 * a screen looks like with data.
 */
object PreviewData {
    private val now = Instant.parse("2026-07-22T10:00:00Z")

    private fun l(
        id: String, platform: PlatformId, title: String, priceCents: Long,
        currency: Currency = Currency.EUR, condition: Condition? = null,
        city: String? = null, country: String? = null, sold: Boolean = false,
        soldDate: Instant? = null,
    ) = Listing(
        id = "$platform:$id", platformId = platform, externalId = id,
        url = "https://example.com/$id", title = title,
        price = Money(priceCents, currency), condition = condition,
        location = if (city != null) Location(city = city, country = country) else null,
        sold = sold, soldDate = soldDate, scrapedAt = now,
        // A photo the renderer can actually load. A real listing carries an address at a
        // market; a drawn one carries a file, so a render shows the image column a card
        // really has rather than pretending a card is all text.
        imageUrls = listOf(photo(id)),
    )

    /** Stand-in photos, named rather than located: only whoever draws them knows where
     *  they are on disk, and an object initialises the moment it is first touched, which
     *  is too early for anyone to have told it. */
    private fun photo(id: String): String {
        val names = listOf("sander.jpg", "belt.jpg", "drum.jpg", "edge.jpg")
        val which = names[(id.hashCode().let { if (it < 0) -it else it }) % names.size]
        return "sample-photos/$which"
    }

    val active: List<Listing> = listOf(
        l("1", PlatformId.KLEINANZEIGEN, "Lägler Hummel Parkettschleifmaschine Bandschleifer", 250_00, city = "Steinen", country = "DE", condition = Condition.USED),
        l("2", PlatformId.EBAY_DE, "Parkettschleifmaschine Trommelschleifer 220V Profi", 380_00, condition = Condition.USED),
        l("3", PlatformId.RICARDO, "Bona Belt Parkettschleifmaschine", 890_00, Currency.CHF, city = "Bern", country = "CH", condition = Condition.USED),
        l("4", PlatformId.WILLHABEN, "Parkettschleifmaschine Lägler Elf", 400_00, city = "Graz", country = "AT", condition = Condition.USED),
        l("5", PlatformId.EBAY_IT, "Levigatrice per parquet Lägler Hummel", 650_00, city = "Milano", country = "IT", condition = Condition.USED),
        l("6", PlatformId.EBAY_DE, "Parkettschleifmaschine NEU Bandschleifmaschine 2200W", 898_00, condition = Condition.NEW),
        l("7", PlatformId.KLEINANZEIGEN, "Parkett Schleifmaschine Walzenschleifer mieten-frei", 300_00, city = "München", country = "DE", condition = Condition.USED),
        l("8", PlatformId.MARKTPLAATS, "Parketschuurmachine Lägler Hummel", 720_00, city = "Amsterdam", country = "NL", condition = Condition.USED),
        l("9", PlatformId.EBAY_ES, "Lijadora de parquet profesional", 540_00, city = "Madrid", country = "ES", condition = Condition.USED),
        l("10", PlatformId.EBAY_DE, "Parkettschleifmaschine Neugerät mit Absaugung", 1149_00, condition = Condition.NEW),
        l("11", PlatformId.RICARDO, "Parkettschleifmaschine Kunzle & Tasin", 1200_00, Currency.CHF, city = "Zürich", country = "CH", condition = Condition.USED),
        l("12", PlatformId.WILLHABEN, "Parkettschleifmaschine Set mit Kantenschleifer", 950_00, city = "Wien", country = "AT", condition = Condition.NEW),
    )

    val sold: List<Listing> = listOf(
        l("s1", PlatformId.EBAY_DE, "Parkettschleifmaschine Lägler Hummel", 780_00, sold = true, soldDate = Instant.parse("2026-07-19T09:00:00Z")),
        l("s2", PlatformId.EBAY_DE, "Parkettschleifmaschine Trommelschleifer", 620_00, sold = true, soldDate = Instant.parse("2026-07-14T09:00:00Z")),
        l("s3", PlatformId.EBAY_DE, "Parkettschleifmaschine Profi 220V", 940_00, sold = true, soldDate = Instant.parse("2026-07-08T09:00:00Z")),
        l("s4", PlatformId.EBAY_DE, "Parkettschleifmaschine mit Absaugung", 700_00, sold = true, soldDate = Instant.parse("2026-06-30T09:00:00Z")),
    )

    /** What the watched searches have found since they were last opened. */
    val savedStatus: List<io.github.tieo.arbay.model.SavedSearchStatus> = listOf(
        io.github.tieo.arbay.model.SavedSearchStatus("sample-0", watched = true, lastRunAtMillis = null, newSinceOpened = 6),
        io.github.tieo.arbay.model.SavedSearchStatus("sample-1", watched = true, lastRunAtMillis = null, newSinceOpened = 0),
        io.github.tieo.arbay.model.SavedSearchStatus("sample-2", watched = false),
    )

    /** Saved searches, so Home renders what it looks like once it is used. */
    val saved: List<io.github.tieo.arbay.model.TrackedProduct> = listOf(
        Triple("Parkettschleifmaschine", listOf(PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE, PlatformId.MARKTPLAATS), io.github.tieo.arbay.model.MarketGroup.GENERAL),
        Triple(
            "VW Crafter L3H2",
            listOf(PlatformId.MOBILE_DE, PlatformId.AUTOSCOUT24, PlatformId.AUTOSCOUT24_IT, PlatformId.AUTOSCOUT24_FR, PlatformId.WILLHABEN),
            io.github.tieo.arbay.model.MarketGroup.VEHICLES,
        ),
        Triple("Bosch GWS 18V", listOf(PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE), io.github.tieo.arbay.model.MarketGroup.GENERAL),
    ).mapIndexed { index, (name, platforms, category) ->
        io.github.tieo.arbay.model.TrackedProduct(
            id = "sample-$index",
            name = name,
            searchQuery = io.github.tieo.arbay.model.SearchQuery(text = name, platforms = platforms, category = category),
            createdAt = now,
        )
    }

    /** What each market answered, including the ways an answer can fail. */
    val marketAnswers: List<io.github.tieo.arbay.ui.viewmodel.PlatformStatus> = listOf(
        answer(PlatformId.KLEINANZEIGEN, PlatformSearchStatus.DONE, raw = 42, kept = 31),
        answer(PlatformId.EBAY_DE, PlatformSearchStatus.DONE, raw = 18, kept = 12, hasMore = true),
        answer(PlatformId.SUBITO, PlatformSearchStatus.DONE, raw = 7, kept = 5, term = "levigatrice per parquet"),
        answer(PlatformId.MARKTPLAATS, PlatformSearchStatus.DONE, raw = 0, kept = 0, term = "parketschuurmachine"),
        answer(PlatformId.RICARDO, PlatformSearchStatus.TIMEOUT, raw = 0, kept = 0),
        answer(PlatformId.MOBILE_DE, PlatformSearchStatus.IP_BLOCKED, raw = 0, kept = 0, error = "403"),
    )

    private fun answer(
        platform: PlatformId,
        status: PlatformSearchStatus,
        raw: Int,
        kept: Int,
        term: String? = null,
        error: String? = null,
        hasMore: Boolean = false,
    ) = io.github.tieo.arbay.ui.viewmodel.PlatformStatus(
        platformId = platform.name,
        platformName = platform.displayName,
        status = status,
        resultCount = kept,
        rawCount = raw,
        error = error,
        queryUsed = term,
        hasMore = hasMore,
    )

    /** What the sample markets can do, mirroring what their crawlers declare. */
    val marketAbilities: Map<PlatformId, io.github.tieo.arbay.model.MarketCapability> = mapOf(
        PlatformId.KLEINANZEIGEN to io.github.tieo.arbay.model.MarketCapability(
            PlatformId.KLEINANZEIGEN, paginates = true, nativeCriteria = setOf("FUEL", "GEARBOX"),
            relatedSearches = true, listingAge = true, location = true, detailSpecs = true,
        ),
        PlatformId.EBAY_DE to io.github.tieo.arbay.model.MarketCapability(
            PlatformId.EBAY_DE, paginates = true, soldListings = true,
        ),
        PlatformId.SUBITO to io.github.tieo.arbay.model.MarketCapability(
            PlatformId.SUBITO, paginates = true, location = true,
        ),
        PlatformId.MARKTPLAATS to io.github.tieo.arbay.model.MarketCapability(
            PlatformId.MARKTPLAATS, paginates = true, location = true, detailSpecs = true,
        ),
    )

    /** A free-item profile, so Home renders with the card the real app pins at the top. */
    val freeItemProfile = io.github.tieo.arbay.model.FreeItemProfile(
        description = "tools, wood, anything for the workshop",
        location = "Frankfurt (Oder)",
        radiusKm = 30,
    )

    /** Markets as the filters list shows them, from the sample listings. Distances are
     *  what a search from southern Germany would measure, so the rendered list is in the
     *  order someone there would see. */
    private val nearestByMarket = mapOf(
        PlatformId.KLEINANZEIGEN to 34.0,
        PlatformId.EBAY_DE to 61.0,
        PlatformId.RICARDO to 148.0,
        PlatformId.WILLHABEN to 310.0,
        PlatformId.SUBITO to 402.0,
        PlatformId.EBAY_IT to 455.0,
        PlatformId.MARKTPLAATS to 520.0,
        PlatformId.TWEEDEHANDS to 545.0,
        PlatformId.EBAY_ES to 1180.0,
    )

    val marketChoices: List<io.github.tieo.arbay.ui.screen.MarketChoice> =
        active.groupBy { it.platformId }.map { (platform, items) ->
            io.github.tieo.arbay.ui.screen.MarketChoice(
                platform = platform,
                name = platform.displayName,
                country = io.github.tieo.arbay.model.MarketSets.countryOf(platform),
                count = items.size,
                nearestKm = nearestByMarket[platform],
            )
        }

    /** The same markets plus the ones that were asked and had nothing to give, which is what the
     *  picker shows after a search where mobile.de and Kleinanzeigen came back empty. */
    val marketChoicesWithEmpties: List<io.github.tieo.arbay.ui.screen.MarketChoice> =
        marketChoices + listOf(
            io.github.tieo.arbay.ui.screen.MarketChoice(
                platform = PlatformId.MOBILE_DE,
                name = PlatformId.MOBILE_DE.displayName,
                country = io.github.tieo.arbay.model.MarketSets.countryOf(PlatformId.MOBILE_DE),
                count = 0,
                emptyBecause = "nothing there",
            ),
            io.github.tieo.arbay.ui.screen.MarketChoice(
                platform = PlatformId.AUTOSCOUT24,
                name = PlatformId.AUTOSCOUT24.displayName,
                country = io.github.tieo.arbay.model.MarketSets.countryOf(PlatformId.AUTOSCOUT24),
                count = 0,
                emptyBecause = "blocked",
            ),
        )

    // ── The states a search can be in ────────────────────────────────────────
    //
    // A screen is not one picture. These are the answers a set of markets can
    // give: still working, all in, some unable, none of them holding anything.

    /** Two markets still out, the rest in. */
    val stillAsking: List<io.github.tieo.arbay.ui.viewmodel.PlatformStatus> = marketAnswers.take(4) +
        listOf(
            answer(PlatformId.MARKTPLAATS, PlatformSearchStatus.SEARCHING, raw = 0, kept = 0),
            answer(PlatformId.EBAY_IT, PlatformSearchStatus.SEARCHING, raw = 0, kept = 0),
        )

    /** Every market answered, none of them badly. */
    val allAnswered: List<io.github.tieo.arbay.ui.viewmodel.PlatformStatus> = listOf(
        answer(PlatformId.KLEINANZEIGEN, PlatformSearchStatus.DONE, raw = 42, kept = 31),
        answer(PlatformId.EBAY_DE, PlatformSearchStatus.DONE, raw = 18, kept = 12, hasMore = true),
        answer(PlatformId.SUBITO, PlatformSearchStatus.DONE, raw = 7, kept = 5, term = "levigatrice per parquet"),
        answer(PlatformId.MARKTPLAATS, PlatformSearchStatus.DONE, raw = 11, kept = 6, term = "parketschuurmachine"),
    )

    /** Nobody had anything: everyone answered, everyone empty. */
    val nobodyHadAnything: List<io.github.tieo.arbay.ui.viewmodel.PlatformStatus> =
        allAnswered.map { it.copy(resultCount = 0, rawCount = 0, hasMore = false) }

    /** A market holding a captcha open, which is neither an answer nor a failure. */
    val captchaHeld: List<io.github.tieo.arbay.ui.viewmodel.PlatformStatus> = listOf(
        answer(PlatformId.KLEINANZEIGEN, PlatformSearchStatus.DONE, raw = 42, kept = 31),
        answer(PlatformId.MOBILE_DE, PlatformSearchStatus.CAPTCHA, raw = 0, kept = 0)
            .copy(captchaUrl = "https://arbay.example/captcha/mobile-de"),
        answer(PlatformId.BILBASEN, PlatformSearchStatus.CAPTCHA, raw = 0, kept = 0)
            .copy(captchaUrl = "https://arbay.example/captcha/bilbasen"),
    )

    /** Every market failed, in each of the ways they fail. */
    val everyoneFailed: List<io.github.tieo.arbay.ui.viewmodel.PlatformStatus> = listOf(
        answer(PlatformId.KLEINANZEIGEN, PlatformSearchStatus.IP_BLOCKED, raw = 0, kept = 0, error = "403"),
        answer(PlatformId.EBAY_DE, PlatformSearchStatus.TIMEOUT, raw = 0, kept = 0),
        answer(PlatformId.RICARDO, PlatformSearchStatus.BLOCKED, raw = 0, kept = 0,
            error = "Cooling down after a block (~17 min left)"),
        answer(PlatformId.IDEALO, PlatformSearchStatus.ERROR, raw = 0, kept = 0,
            error = "idealo:parkettschleifmaschine: HTTP 503"),
    )

    // ── Recent searches, for Discovery's empty state ─────────────────────────────
    // Two shapes: a plain search narrowed by hand, and a vehicle search with real criteria — the
    // two kinds of thing a search's filters can hold.
    val searchHistory: List<io.github.tieo.arbay.history.SearchHistoryEntry> = listOf(
        io.github.tieo.arbay.history.SearchHistoryEntry(
            name = "Parkettschleifmaschine",
            searchQuery = SearchQuery(
                text = "parkettschleifmaschine",
                minPrice = Money(25000, Currency.EUR),
                maxPrice = Money(90000, Currency.EUR),
                condition = listOf(Condition.USED),
                excludeKeywords = listOf("defekt"),
                category = io.github.tieo.arbay.model.MarketGroup.GENERAL,
            ),
            lastRunAt = now,
        ),
        io.github.tieo.arbay.history.SearchHistoryEntry(
            name = "Volkswagen Crafter",
            searchQuery = SearchQuery(
                text = "Volkswagen Crafter",
                carFilters = CarFilters(
                    firstRegFromYear = 2019, maxMileageKm = 150000, minPowerKw = 110,
                    transmission = Transmission.AUTOMATIC,
                ),
                category = io.github.tieo.arbay.model.MarketGroup.VEHICLES,
            ),
            lastRunAt = now,
        ),
    )
}
