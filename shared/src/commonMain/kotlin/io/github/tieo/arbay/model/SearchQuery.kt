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
    // Free-form description of the ideal car ("well kept, tow bar, no accidents, full service
    // history"). Not a hard filter — the server embeds it and ranks results by semantic similarity
    // to each listing's text, surfacing the best matches first. Local embeddings, no API cost.
    val idealDescription: String? = null,
    // Filter strictness (behaviour, not a constraint — excluded from isEmpty). Specs read from the
    // listing text always count (a stated "345.000 km" is honoured), so filtering works on sites with
    // no structured data. strictUnknown: exclude a listing whose filtered spec can't be determined at
    // all (precise but loses coverage); off = keep unknowns.
    val strictUnknown: Boolean = false,
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
            descriptionContains.isNullOrBlank() && idealDescription.isNullOrBlank()
}

/** Car filters carried inside a saved search, so opening a bookmark reruns it with the
 *  same constraints instead of a bare text search. Null when the query has no car filter.
 *
 *  The price bound is not among the stored filters: every search has one, car or not, so it lives
 *  on the query's own minPrice/maxPrice and is overlaid here. Read through this and the filter view
 *  is complete; write through [withCarFilters] and there is still only one copy to disagree with. */
fun SearchQuery.toCarFilters(): CarFilters? {
    val complete = (carFilters ?: CarFilters()).copy(
        minPriceEur = minPrice?.let { (it.amount / 100).toInt() },
        maxPriceEur = maxPrice?.let { (it.amount / 100).toInt() },
    )
    return if (complete.isEmpty) null else complete
}

/** The vehicle criteria of a search, empty when it carries none. Every crawler reads its native
 *  filter parameters from here, so a criterion cannot reach one site and be silently dropped on
 *  another for want of a field on the query. The price band is not among them: it is not
 *  car-specific and lives on minPrice/maxPrice, where a non-car crawler can reach it too. */
val SearchQuery.carCriteria: CarFilters get() = toCarFilters() ?: CarFilters()

/** Store a filter set on the query, lifting its price bound out to minPrice/maxPrice — the one
 *  place a price is kept, whether or not the search is for a car. */
fun SearchQuery.withCarFilters(filters: CarFilters?): SearchQuery = copy(
    carFilters = filters?.copy(minPriceEur = null, maxPriceEur = null)?.takeUnless { it.isEmpty },
    minPrice = filters?.minPriceEur?.let { Money(it * 100L, Currency.EUR) },
    maxPrice = filters?.maxPriceEur?.let { Money(it * 100L, Currency.EUR) },
)

/** Narrow the search to a price band, in whole euro. Used by the results slider, which edits the
 *  same bound the search form sets. */
fun SearchQuery.withPriceRangeEur(minEur: Int?, maxEur: Int?): SearchQuery = copy(
    minPrice = minEur?.let { Money(it * 100L, Currency.EUR) },
    maxPrice = maxEur?.let { Money(it * 100L, Currency.EUR) },
)

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
    // The searcher's position (from the device GPS). When set, the server geocodes each listing and
    // fills in distanceKm, so results can be shown and sorted nearest-first.
    val userLat: Double? = null,
    val userLon: Double? = null,
    val maxPages: Int? = null,      // override crawler's default page limit (null = use CrawlerConfig)
    val startPage: Int = 1,         // start from this page (for paginated batches)
    // Vehicle criteria, read through carCriteria: crawlers turn what their site supports into
    // native URL parameters, and post-filtering enforces the rest.
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
