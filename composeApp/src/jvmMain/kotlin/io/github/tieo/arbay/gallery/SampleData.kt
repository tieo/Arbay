package io.github.tieo.arbay.gallery

import io.github.tieo.arbay.model.*
import kotlinx.datetime.Instant

/** Realistic sample listings for the gallery — a "parkettschleifmaschine" search: real machines,
 *  mixed platforms/countries, new and used, plus a few sold ones with dates for the history chart. */
object SampleData {
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
    )

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

    /** Saved searches, so Home renders what it looks like once it is used. */
    val saved: List<io.github.tieo.arbay.model.TrackedProduct> = listOf(
        "Parkettschleifmaschine" to listOf(PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE, PlatformId.MARKTPLAATS),
        "VW Crafter L3H2" to listOf(
            PlatformId.MOBILE_DE, PlatformId.AUTOSCOUT24, PlatformId.AUTOSCOUT24_IT,
            PlatformId.AUTOSCOUT24_FR, PlatformId.WILLHABEN,
        ),
        "Bosch GWS 18V" to listOf(PlatformId.KLEINANZEIGEN, PlatformId.EBAY_DE),
    ).mapIndexed { index, (name, platforms) ->
        io.github.tieo.arbay.model.TrackedProduct(
            id = "sample-$index",
            name = name,
            searchQuery = io.github.tieo.arbay.model.SearchQuery(text = name, platforms = platforms),
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
}
