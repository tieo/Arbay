package io.github.tieo.arbay.model

import kotlinx.serialization.Serializable

@Serializable
enum class Transmission {
    AUTOMATIC, MANUAL;

    companion object {
        fun parse(raw: String?): Transmission? {
            val s = raw?.lowercase()?.trim() ?: return null
            return when {
                s.isBlank() -> null
                "automat" in s || "dsg" in s || "tiptronic" in s || "s tronic" in s ||
                    "s-tronic" in s || "pdk" in s || "automaat" in s -> AUTOMATIC
                "schalt" in s || "manuell" in s || "manual" in s || "handgeschakeld" in s ||
                    "manuál" in s || "manuel" in s -> MANUAL
                else -> null
            }
        }
    }
}

/** Structured car-search criteria, carried from the UI to the crawler search endpoint,
 *  which turns them into per-site URL filter parameters. Prices are in EUR. */
@Serializable
data class CarFilters(
    val firstRegFromYear: Int? = null,
    val firstRegToYear: Int? = null,
    val minMileageKm: Int? = null,
    val maxMileageKm: Int? = null,
    val minPriceEur: Int? = null,
    val maxPriceEur: Int? = null,
    val minPowerKw: Int? = null,
    val maxPowerKw: Int? = null,
    val transmission: Transmission? = null,
    // Multi-select: a listing matches if its value is in the set (empty = no constraint).
    val fuels: Set<Fuel> = emptySet(),
    val bodyTypes: Set<BodyType> = emptySet(),
    val conditions: Set<VehicleCondition> = emptySet(),
    val colors: Set<String> = emptySet(),
    val drivetrain: Drivetrain? = null,
    val minDoors: Int? = null,
    val minSeats: Int? = null,
    val minEmissionEuro: Int? = null,   // e.g. 6 = at least Euro 6
    val sellerType: SellerType? = null,
    // Panel-van size classes (multi-select): a listing matches if its explicit L/H code is in
    // the set. Listings with no stated code pass (unknown != excluded).
    val vanLengths: Set<Int> = emptySet(),   // L1..L4
    val vanHeights: Set<Int> = emptySet(),   // H1..H3
    // Free text that must appear in the listing's title or description. A literal match on
    // text we already have, so it works on every platform and can safely exclude.
    val descriptionContains: String? = null,
) {
    val isEmpty: Boolean get() =
        firstRegFromYear == null && firstRegToYear == null &&
            minMileageKm == null && maxMileageKm == null &&
            minPriceEur == null && maxPriceEur == null &&
            minPowerKw == null && maxPowerKw == null && transmission == null &&
            fuels.isEmpty() && bodyTypes.isEmpty() && conditions.isEmpty() && colors.isEmpty() &&
            drivetrain == null && minDoors == null && minSeats == null &&
            minEmissionEuro == null && sellerType == null &&
            vanLengths.isEmpty() && vanHeights.isEmpty() &&
            descriptionContains.isNullOrBlank()
}

/** Car filters carried inside a saved search, so opening a bookmark reruns it with the
 *  same constraints instead of a bare text search. Null when the query has no car filter. */
fun SearchQuery.toCarFilters(): CarFilters? {
    // The full carFilters wins; fall back to the legacy individual fields for older saved
    // searches that predate it.
    carFilters?.takeUnless { it.isEmpty }?.let { return it }
    val filters = CarFilters(
        firstRegFromYear = firstRegFromYear,
        firstRegToYear = firstRegToYear,
        maxMileageKm = maxMileageKm,
        maxPriceEur = maxPrice?.let { (it.amount / 100).toInt() },
        minPowerKw = minPowerKw,
        transmission = transmission,
        descriptionContains = descriptionContains,
    )
    return if (filters.isEmpty) null else filters
}

@Serializable
data class SearchQuery(
    val text: String,
    val platforms: List<PlatformId> = PlatformId.entries,
    val minPrice: Money? = null,
    val maxPrice: Money? = null,
    val condition: List<Condition>? = null,
    val soldOnly: Boolean = false,
    val freeOnly: Boolean = false,
    val excludeKeywords: List<String> = emptyList(),
    val location: String? = null,   // city name / zip for location-based search
    val radiusKm: Int = 30,         // search radius in km
    val maxPages: Int? = null,      // override crawler's default page limit (null = use CrawlerConfig)
    val startPage: Int = 1,         // start from this page (for paginated batches)
    // Vehicle filters — applied at the source by crawlers that support them (e.g. AutoScout24).
    val firstRegFromYear: Int? = null,  // earliest first-registration year
    val firstRegToYear: Int? = null,    // latest first-registration year
    val maxMileageKm: Int? = null,      // mileage ceiling
    val minPowerKw: Int? = null,        // minimum engine power in kW
    val transmission: Transmission? = null,
    val descriptionContains: String? = null,  // free text required in title/description
    // The full filter set, enforced by post-filtering. The individual fields above stay for
    // the crawlers that turn them into native URL params; everything else lives here.
    val carFilters: CarFilters? = null,
) {
    /** Search text with negative keywords and OR logic resolved — for platforms that don't support exclusion/OR syntax.
     *  For OR queries, picks the group with the most tokens (most specific variant). */
    val positiveText: String
        get() {
            val withoutNegatives = text.split(" ")
                .filter { it.isNotBlank() && !it.startsWith("-") }
                .joinToString(" ")
            return if (withoutNegatives.contains(" OR ", ignoreCase = true)) {
                // Split on OR and pick the group with the most tokens (most descriptive)
                withoutNegatives.split(Regex("\\s+OR\\s+", RegexOption.IGNORE_CASE))
                    .maxByOrNull { it.trim().split("\\s+".toRegex()).size }
                    ?.trim() ?: withoutNegatives
            } else {
                withoutNegatives
            }
        }
}
